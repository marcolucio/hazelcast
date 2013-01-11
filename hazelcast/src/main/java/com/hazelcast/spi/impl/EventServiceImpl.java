/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl;

import com.hazelcast.cluster.JoinOperation;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.executor.ExecutorThreadFactory;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.instance.Node;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.Packet;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.nio.serialization.SerializationContext;
import com.hazelcast.spi.*;
import com.hazelcast.spi.annotation.PrivateApi;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * @mdogan 12/14/12
 */

public class EventServiceImpl implements EventService {
    private static final EventRegistration[] EMPTY_REGISTRATIONS = new EventRegistration[0];

    private final ILogger logger;
    private final NodeEngineImpl nodeEngine;
    private final ConcurrentMap<String, EventServiceSegment> segments;
    final ExecutorService eventExecutorService;
    private final SerializationContext serializationContext;

    EventServiceImpl(NodeEngineImpl nodeEngine) {
        this.nodeEngine = nodeEngine;
        logger = nodeEngine.getLogger(EventService.class.getName());
        final Node node = nodeEngine.getNode();
        eventExecutorService = Executors.newSingleThreadExecutor(
                new ExecutorThreadFactory(node.threadGroup, node.hazelcastInstance,
                        node.getConfig().getClassLoader()) {
                    protected String newThreadName() {
                        return node.getThreadNamePrefix("event");
                    }
                });
        segments = new ConcurrentHashMap<String, EventServiceSegment>();
        serializationContext = this.nodeEngine.getSerializationContext();
    }

    public EventRegistration registerLocalListener(String serviceName, String topic, Object listener) {
        return registerListenerInternal(serviceName, topic, new EmptyFilter(), listener, true);
    }

    public EventRegistration registerLocalListener(String serviceName, String topic, EventFilter filter, Object listener) {
        return registerListenerInternal(serviceName, topic, filter, listener, true);
    }

    public EventRegistration registerListener(String serviceName, String topic, Object listener) {
        return registerListenerInternal(serviceName, topic, new EmptyFilter(), listener, false);
    }

    public EventRegistration registerListener(String serviceName, String topic, EventFilter filter, Object listener) {
        return registerListenerInternal(serviceName, topic, filter, listener, false);
    }

    private EventRegistration registerListenerInternal(String serviceName, String topic, EventFilter filter,
                                                       Object listener, boolean localOnly) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener required!");
        }
        if (filter == null) {
            throw new IllegalArgumentException("EventFilter required!");
        }
        EventServiceSegment segment = getSegment(serviceName, true);
        Registration reg = new Registration(createId(serviceName), serviceName, topic, filter,
                nodeEngine.getThisAddress(), listener, localOnly);

        if (segment.addRegistration(topic, reg)) {
            if (!localOnly) {
                final RegistrationOperation op = new RegistrationOperation(reg);
                invokeOnOtherNodes(serviceName, op);
            }
            return reg;
        } else {
            return null;
        }
    }

    private boolean handleRegistration(Registration reg) {
        EventServiceSegment segment = getSegment(reg.serviceName, true);
        return segment.addRegistration(reg.topic, reg);
    }

    public void deregisterListener(String serviceName, String topic, String id) {
        final EventServiceSegment segment = getSegment(serviceName, false);
        if (segment != null) {
            final Registration reg = segment.removeRegistration(topic, id);
            if (reg != null && !reg.isLocalOnly()) {
                final DeregistrationOperation op = new DeregistrationOperation(topic, id);
                invokeOnOtherNodes(serviceName, op);
            }
        }
    }

    private void deregisterSubscriber(String serviceName, String topic, String id) {
        final EventServiceSegment segment = getSegment(serviceName, false);
        if (segment != null) {
            segment.removeRegistration(topic, id);
        }
    }

    private void invokeOnOtherNodes(String serviceName, Operation op) {
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        Collection<Future> calls = new ArrayList<Future>(members.size());
        for (MemberImpl member : members) {
            if (!member.localMember()) {
                Invocation inv = nodeEngine.getOperationService().createInvocationBuilder(serviceName,
                        op, member.getAddress()).build();
                calls.add(inv.invoke());
            }
        }
        for (Future f : calls) {
            try {
                f.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } catch (TimeoutException ignored) {
            } catch (ExecutionException e) {
                throw new HazelcastException(e);
            }
        }
    }

    private String createId(String serviceName) {
        return serviceName + ":" + UUID.randomUUID().toString();
    }

    public EventRegistration[] getRegistrationsAsArray(String serviceName, String topic) {
        final EventServiceSegment segment = getSegment(serviceName, false);
        if (segment != null) {
            final Collection<Registration> registrations = segment.getRegistrations(topic, false);
            return registrations != null && !registrations.isEmpty()
                    ? registrations.toArray(new Registration[registrations.size()])
                    : EMPTY_REGISTRATIONS;
        }
        return EMPTY_REGISTRATIONS;
    }

    public Collection<EventRegistration> getRegistrations(String serviceName, String topic) {
        final EventServiceSegment segment = getSegment(serviceName, false);
        if (segment != null) {
            final Collection<Registration> registrations = segment.getRegistrations(topic, false);
            return registrations != null && !registrations.isEmpty()
                    ? Collections.<EventRegistration>unmodifiableCollection(registrations)
                    : Collections.<EventRegistration>emptySet();
        }
        return Collections.emptySet();
    }

    public void publishEvent(String serviceName, EventRegistration registration, Object event) {
        if (!(registration instanceof Registration)) {
            throw new IllegalArgumentException();
        }
        final Registration reg = (Registration) registration;
        if (reg.isLocal()) {
            eventExecutorService.execute(new LocalEventDispatcher(serviceName, event, reg.listener));
        } else {
            final Address subscriber = registration.getSubscriber();
            final Data data = nodeEngine.toData(new EventPacket(registration.getId(), serviceName, event));
            final Packet packet = new Packet(data, serializationContext);
            packet.setHeader(Packet.HEADER_EVENT, true);
            // TODO: event publishing requires flow control mechanism!
            nodeEngine.getClusterService().send(packet, subscriber);
        }
    }

    public void publishEvent(String serviceName, Collection<EventRegistration> registrations, Object event) {
        final Iterator<EventRegistration> iter = registrations.iterator();
        Data eventData = null;
        while (iter.hasNext()) {
            EventRegistration registration = iter.next();
            if (!(registration instanceof Registration)) {
                throw new IllegalArgumentException();
            }
            final Registration reg = (Registration) registration;
            if (reg.isLocal()) {
                eventExecutorService.execute(new LocalEventDispatcher(serviceName, event, reg.listener));
            } else {
                if (eventData == null) {
                    eventData = nodeEngine.toData(event);
                }
                final Address subscriber = registration.getSubscriber();
                final Data data = nodeEngine.toData(new EventPacket(registration.getId(), serviceName, eventData));
                final Packet packet = new Packet(data, serializationContext);
                packet.setHeader(Packet.HEADER_EVENT, true);
                // TODO: event publishing requires flow control mechanism!
                nodeEngine.getClusterService().send(packet, subscriber);
            }
        }
    }

    public void executeEvent(Runnable eventRunnable) {
        eventExecutorService.execute(eventRunnable);
    }

    private EventServiceSegment getSegment(String service, boolean forceCreate) {
        EventServiceSegment segment = segments.get(service);
        if (segment == null && forceCreate) {
            segment = new EventServiceSegment(service);
            EventServiceSegment current = segments.putIfAbsent(service, segment);
            segment = current == null ? segment : current;
        }
        return segment;
    }

    @PrivateApi
    void handleEvent(Packet packet) {
        eventExecutorService.execute(new EventPacketProcessor(packet));
    }

    /**
     * Post join operations must be lock free; means no locks at all;
     * no partition locks, no key-based locks, no service level locks!
     *
     * Post join operations should return response, at least a null response.
     *
     * Also making post-join operation a JoinOperation will help a lot.
     */
    @PrivateApi
    Operation getPostJoinOperation() {
        final Collection<Registration> registrations = new LinkedList<Registration>();
        for (EventServiceSegment segment : segments.values()) {
            for (Registration reg : segment.registrationIdMap.values()) {
                if (!reg.isLocalOnly()) {
                    registrations.add(reg);
                }
            }
        }
        return registrations.isEmpty() ? null : new PostJoinRegistrationOperation(registrations);
    }

    void shutdown() {
        logger.log(Level.FINEST, "Stopping event executor...");
        eventExecutorService.shutdownNow();
        for (EventServiceSegment segment : segments.values()) {
            segment.clear();
        }
        segments.clear();
    }

    void onMemberLeft(MemberImpl member) {
        final Address address = member.getAddress();
        for (EventServiceSegment segment : segments.values()) {
            segment.onMemberLeft(address);
        }
    }

    private class EventServiceSegment {
        final String serviceName;
        final ConcurrentMap<String, Collection<Registration>> registrations
                = new ConcurrentHashMap<String, Collection<Registration>>();

        final ConcurrentMap<String, Registration> registrationIdMap = new ConcurrentHashMap<String, Registration>();

        EventServiceSegment(String serviceName) {
            this.serviceName = serviceName;
        }

        private Collection<Registration> getRegistrations(String topic, boolean forceCreate) {
            Collection<Registration> listenerList = registrations.get(topic);
            if (listenerList == null && forceCreate) {
                listenerList = Collections.newSetFromMap(new ConcurrentHashMap<Registration, Boolean>());
                Collection<Registration> current = registrations.putIfAbsent(topic, listenerList);
                listenerList = current == null ? listenerList : current;
            }
            return listenerList;
        }

        private boolean addRegistration(String topic, Registration registration) {
            final Collection<Registration> registrations = getRegistrations(topic, true);
            if (registrations.add(registration)) {
                registrationIdMap.put(registration.id, registration);
                return true;
            }
            return false;
        }

        private Registration removeRegistration(String topic, String id) {
            final Registration registration = registrationIdMap.remove(id);
            if (registration != null) {
                final Collection<Registration> all = registrations.get(topic);
                if (all != null) {
                    all.remove(registration);
                }
            }
            return registration;
        }

        void clear() {
            registrations.clear();
            registrationIdMap.clear();
        }

        void onMemberLeft(Address address) {
            for (Collection<Registration> all : registrations.values()) {
                Iterator<Registration> iter = all.iterator();
                while (iter.hasNext()) {
                    Registration reg = iter.next();
                    if (address.equals(reg.getSubscriber())) {
                        iter.remove();
                        registrationIdMap.remove(reg.id);
                    }
                }
            }
        }
    }

    private class EventPacketProcessor implements Runnable {
        private Packet packet;

        public EventPacketProcessor(Packet packet) {
            this.packet = packet;
        }

        public void run() {
            Data data = packet.getValue();
            EventPacket eventPacket = (EventPacket) nodeEngine.toObject(data);
            Object eventObject = eventPacket.event;
            if (eventObject instanceof Data) {
                eventObject = nodeEngine.toObject(eventObject);
            }
            final String serviceName = eventPacket.serviceName;
            EventPublishingService service = nodeEngine.getService(serviceName);
            if (service == null) {
                logger.log(Level.WARNING, "There is no service named: " + serviceName);
                return;
            }
            EventServiceSegment segment = getSegment(serviceName, false);
            if (segment == null) {
                logger.log(Level.WARNING, "No service registration found for " + serviceName);
                return;
            }
            Registration registration = segment.registrationIdMap.get(eventPacket.id);
            if (registration == null) {
                logger.log(Level.WARNING, "No registration found for " + serviceName + " / " + eventPacket.id);
                return;
            }
            if (!registration.isLocal()) {
                logger.log(Level.WARNING, "Invalid target for  " + registration);
                return;
            }
            service.dispatchEvent(eventObject, registration.listener);
        }
    }

    private class LocalEventDispatcher implements Runnable {
        final String serviceName;
        final Object event;
        final Object listener;

        private LocalEventDispatcher(String serviceName, Object event, Object listener) {
            this.serviceName = serviceName;
            this.event = event;
            this.listener = listener;
        }

        public void run() {
            EventPublishingService service = nodeEngine.getService(serviceName);
            service.dispatchEvent(event, listener);
        }
    }

    public static class Registration implements EventRegistration {
        private String id;
        private String serviceName;
        private String topic;
        private EventFilter filter;
        private Address subscriber;
        private transient boolean localOnly;
        private transient Object listener;

        public Registration() {
        }

        public Registration(String id, String serviceName, String topic,
                            EventFilter filter, Address subscriber, Object listener, boolean localOnly) {
            this.filter = filter;
            this.id = id;
            this.listener = listener;
            this.serviceName = serviceName;
            this.topic = topic;
            this.subscriber = subscriber;
            this.localOnly = localOnly;
        }

        public EventFilter getFilter() {
            return filter;
        }

        public String getId() {
            return id;
        }

        public Address getSubscriber() {
            return subscriber;
        }

        public boolean isLocalOnly() {
            return localOnly;
        }

        private boolean isLocal() {
            return listener != null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Registration that = (Registration) o;

            if (!serviceName.equals(that.serviceName)) return false;
            if (!subscriber.equals(that.subscriber)) return false;
            if (!filter.equals(that.filter)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = serviceName.hashCode();
            result = 31 * result + filter.hashCode();
            result = 31 * result + subscriber.hashCode();
            return result;
        }

        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeUTF(id);
            out.writeUTF(serviceName);
            out.writeUTF(topic);
            subscriber.writeData(out);
            out.writeObject(filter);
        }

        public void readData(ObjectDataInput in) throws IOException {
            id = in.readUTF();
            serviceName = in.readUTF();
            topic = in.readUTF();
            subscriber = new Address();
            subscriber.readData(in);
            filter = in.readObject();
        }


        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Registration");
            sb.append("{filter=").append(filter);
            sb.append(", id='").append(id).append('\'');
            sb.append(", serviceName='").append(serviceName).append('\'');
            sb.append(", subscriber=").append(subscriber);
            sb.append(", listener=").append(listener);
            sb.append('}');
            return sb.toString();
        }
    }

    public static class EventPacket implements IdentifiedDataSerializable {

        private String id;
        private String serviceName;
        private Object event;

        public EventPacket() {
        }

        EventPacket(String id, String serviceName, Object event) {
            this.event = event;
            this.id = id;
            this.serviceName = serviceName;
        }

        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeUTF(id);
            out.writeUTF(serviceName);
            out.writeObject(event);
        }

        public void readData(ObjectDataInput in) throws IOException {
            id = in.readUTF();
            serviceName = in.readUTF();
            event = in.readObject();
        }

        public int getId() {
            return DataSerializerInitHook.EVENT_PACKET;
        }
    }

    public static class EmptyFilter implements EventFilter, DataSerializable {
        public boolean eval(Object arg) {
            return true;
        }
        public void writeData(ObjectDataOutput out) throws IOException {}
        public void readData(ObjectDataInput in) throws IOException {}
    }

    public static class RegistrationOperation extends AbstractOperation {

        private Registration registration;
        private transient boolean response = false;

        public RegistrationOperation() {
        }

        private RegistrationOperation(Registration registration) {
            this.registration = registration;
        }

        public void run() throws Exception {
            EventServiceImpl eventService = (EventServiceImpl) getNodeEngine().getEventService();
            response = eventService.handleRegistration(registration);
        }

        @Override
        public Object getResponse() {
            return response;
        }

        @Override
        public boolean returnsResponse() {
            return true;
        }

        @Override
        protected void writeInternal(ObjectDataOutput out) throws IOException {
            registration.writeData(out);
        }

        @Override
        protected void readInternal(ObjectDataInput in) throws IOException {
            registration = new Registration();
            registration.readData(in);
        }
    }

    public static class DeregistrationOperation extends AbstractOperation {

        private String topic;
        private String id;

        DeregistrationOperation() {
        }

        private DeregistrationOperation(String topic, String id) {
            this.topic = topic;
            this.id = id;
        }

        public void run() throws Exception {
            EventServiceImpl eventService = (EventServiceImpl) getNodeEngine().getEventService();
            eventService.deregisterSubscriber(getServiceName(), topic, id);
        }

        @Override
        public Object getResponse() {
            return true;
        }

        @Override
        public boolean returnsResponse() {
            return true;
        }

        @Override
        protected void writeInternal(ObjectDataOutput out) throws IOException {
            out.writeUTF(topic);
            out.writeUTF(id);
        }

        @Override
        protected void readInternal(ObjectDataInput in) throws IOException {
            topic = in.readUTF();
            id = in.readUTF();
        }
    }

    public static class PostJoinRegistrationOperation extends AbstractOperation implements JoinOperation {

        private Collection<Registration> registrations;

        public PostJoinRegistrationOperation() {
        }

        public PostJoinRegistrationOperation(Collection<Registration> registrations) {
            this.registrations = registrations;
        }

        @Override
        public void run() throws Exception {
            if (registrations != null && registrations.size() > 0) {
                NodeEngineImpl nodeEngine = (NodeEngineImpl) getNodeEngine();
                EventServiceImpl eventService = nodeEngine.eventService;
                for (Registration reg : registrations) {
                    eventService.handleRegistration(reg);
                }
            }
        }

        @Override
        public boolean returnsResponse() {
            return false;
        }

        @Override
        protected void writeInternal(ObjectDataOutput out) throws IOException {
            super.writeInternal(out);
            int len = registrations != null ? registrations.size() : 0;
            out.writeInt(len);
            if (len > 0) {
                for (Registration reg : registrations) {
                    reg.writeData(out);
                }
            }
        }

        @Override
        protected void readInternal(ObjectDataInput in) throws IOException {
            super.readInternal(in);
            int len = in.readInt();
            if (len > 0) {
                registrations = new ArrayList<Registration>(len);
                for (int i = 0; i < len; i++) {
                    Registration reg = new Registration();
                    registrations.add(reg);
                    reg.readData(in);
                }
            }
        }
    }

}
