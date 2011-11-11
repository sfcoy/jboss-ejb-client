/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.jboss.ejb.client.remoting.RemotingConnectionEJBReceiver;
import org.jboss.remoting3.Connection;

/**
 * The public API for an EJB client context.  An EJB client context may be associated with (and used by) one or more threads concurrently.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@SuppressWarnings({"UnnecessaryThis"})
public final class EJBClientContext extends Attachable {

    /**
     * EJB client context selector. By default the {@link ConfigBasedEJBClientContextSelector} is used
     */
    private static volatile ContextSelector<EJBClientContext> SELECTOR = ConfigBasedEJBClientContextSelector.INSTANCE;

    private static final RuntimePermission SET_SELECTOR_PERMISSION = new RuntimePermission("setEJBClientContextSelector");

    static final GeneralEJBClientInterceptor[] GENERAL_INTERCEPTORS;

    static {
        final List<GeneralEJBClientInterceptor> interceptors = new ArrayList<GeneralEJBClientInterceptor>();
        for (GeneralEJBClientInterceptor interceptor : ServiceLoader.load(GeneralEJBClientInterceptor.class)) {
            interceptors.add(interceptor);
        }
        GENERAL_INTERCEPTORS = interceptors.toArray(new GeneralEJBClientInterceptor[interceptors.size()]);
    }

    private final Map<EJBReceiver<?>, EJBReceiverContext> ejbReceiverAssociations = new IdentityHashMap<EJBReceiver<?>, EJBReceiverContext>();

    EJBClientContext() {
    }

    /**
     * Creates and returns a new client context
     *
     * @return Returns the newly created context
     */
    public static EJBClientContext create() {
        return new EJBClientContext();
    }

    /**
     * Sets the EJB client context selector. Replaces the existing selector, which is then returned by this method
     *
     * @param newSelector The selector to set. Cannot be null
     * @return Returns the previosuly set EJB client context selector.
     * @throws SecurityException if a security manager is installed and you do not have the {@code setEJBClientContextSelector}
     *                           {@link RuntimePermission}
     */
    public static ContextSelector<EJBClientContext> setSelector(final ContextSelector<EJBClientContext> newSelector) {
        if (newSelector == null) {
            throw new IllegalArgumentException("EJB client context selector cannot be set to null");
        }
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(SET_SELECTOR_PERMISSION);
        }
        final ContextSelector<EJBClientContext> oldSelector = SELECTOR;
        SELECTOR = newSelector;
        return oldSelector;
    }

    /**
     * Set a constant EJB client context.  Replaces the existing selector, which is then returned by this method
     *
     * @param context the context to set
     * @return Returns the previosuly set EJB client context selector.
     * @throws SecurityException if a security manager is installed and you do not have the {@code setEJBClientContextSelector} {@link RuntimePermission}
     */
    public static ContextSelector<EJBClientContext> setConstantContext(final EJBClientContext context) {
        return setSelector(new ConstantContextSelector<EJBClientContext>(context));
    }

    /**
     * Get the current client context for this thread.
     *
     * @return the current client context
     */
    public static EJBClientContext getCurrent() {
        return SELECTOR.getCurrent();
    }

    /**
     * Get the current client context for this thread, throwing an exception if none is set.
     *
     * @return the current client context
     * @throws IllegalStateException if the current client context is not set
     */
    public static EJBClientContext requireCurrent() throws IllegalStateException {
        final EJBClientContext clientContext = getCurrent();
        if (clientContext == null) {
            throw new IllegalStateException("No EJB client context is available");
        }
        return clientContext;
    }

    /**
     * Register an EJB receiver with this client context.
     *
     * @param receiver the receiver to register
     * @throws IllegalArgumentException If the passed <code>receiver</code> is null
     */
    public void registerEJBReceiver(final EJBReceiver<?> receiver) {
        if (receiver == null) {
            throw new IllegalArgumentException("receiver is null");
        }
        EJBReceiverContext ejbReceiverContext = null;
        synchronized (this.ejbReceiverAssociations) {
            if (this.ejbReceiverAssociations.containsKey(receiver)) {
                // nothing to do
                return;
            }
            ejbReceiverContext = new EJBReceiverContext(receiver, this);
            this.ejbReceiverAssociations.put(receiver, ejbReceiverContext);
        }
        receiver.associate(ejbReceiverContext);
    }

    /**
     * Unregister (a previously registered) EJB receiver from this client context.
     * <p/>
     * This EJB client context will not use this unregistered receiver for any subsequent
     * invocations
     *
     * @param receiver The EJB receiver to unregister
     * @throws IllegalArgumentException If the passed <code>receiver</code> is null
     */
    public void unregisterEJBReceiver(final EJBReceiver<?> receiver) {
        if (receiver == null) {
            throw new IllegalArgumentException("Receiver cannot be null");
        }
        synchronized (this.ejbReceiverAssociations) {
            this.ejbReceiverAssociations.remove(receiver);
        }
    }

    /**
     * Register a Remoting connection with this client context.
     *
     * @param connection the connection to register
     */
    public void registerConnection(final Connection connection) {
        registerEJBReceiver(new RemotingConnectionEJBReceiver(connection));
    }

    Collection<EJBReceiver<?>> getEJBReceivers(final String appName, final String moduleName, final String distinctName) {
        final Collection<EJBReceiver<?>> eligibleEJBReceivers = new HashSet<EJBReceiver<?>>();
        synchronized (this.ejbReceiverAssociations) {
            for (final EJBReceiver<?> ejbReceiver : this.ejbReceiverAssociations.keySet()) {
                if (ejbReceiver.acceptsModule(appName, moduleName, distinctName)) {
                    eligibleEJBReceivers.add(ejbReceiver);
                }
            }
        }
        return eligibleEJBReceivers;
    }

    /**
     * Get the first EJB receiver which matches the given combination of app, module and distinct name.
     *
     * @param appName      the application name, or {@code null} for a top-level module
     * @param moduleName   the module name
     * @param distinctName the distinct name, or {@code null} for none
     * @return the first EJB receiver to match, or {@code null} if none match
     */
    EJBReceiver<?> getEJBReceiver(final String appName, final String moduleName, final String distinctName) {
        final Iterator<EJBReceiver<?>> iterator = getEJBReceivers(appName, moduleName, distinctName).iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    /**
     * Get the first EJB receiver which matches the given combination of app, module and distinct name. If there's
     * no such EJB receiver, then this method throws a {@link IllegalStateException}
     *
     * @param appName      the application name, or {@code null} for a top-level module
     * @param moduleName   the module name
     * @param distinctName the distinct name, or {@code null} for none
     * @return the first EJB receiver to match
     * @throws IllegalStateException If there's no {@link EJBReceiver} which can handle a EJB for the passed combination
     *                               of app, module and distinct name.
     */
    EJBReceiver<?> requireEJBReceiver(final String appName, final String moduleName, final String distinctName)
            throws IllegalStateException {

        EJBReceiver<?> ejbReceiver = null;
        // This is an "optimization"
        // if there's just one EJBReceiver, then we don't check whether it can handle the module. We just
        // assume that it will be able to handle this module (if not, it will throw a NoSuchEJBException anyway)
        // This comes handy in cases where the EJBReceiver might not yet have received a module inventory message
        // from the server and hence wouldn't know whether it can handle a particular app, module, distinct name combination.
        synchronized (this.ejbReceiverAssociations) {
            if (this.ejbReceiverAssociations.size() == 1) {
                ejbReceiver = this.ejbReceiverAssociations.keySet().iterator().next();
            }
        }
        if (ejbReceiver != null) {
            return ejbReceiver;
        }
        // try and find a receiver which can handle this combination
        ejbReceiver = this.getEJBReceiver(appName, moduleName, distinctName);
        if (ejbReceiver == null) {
            throw new IllegalStateException("No EJB receiver available for handling [appName:" + appName + ",modulename:"
                    + moduleName + ",distinctname:" + distinctName + "] combination");
        }
        return ejbReceiver;
    }

    /**
     * Returns a {@link EJBReceiverContext} for the passed <code>receiver</code>. If the <code>receiver</code>
     * hasn't been registered with this {@link EJBClientContext}, either through a call to {@link #registerConnection(org.jboss.remoting3.Connection)}
     * or to {@link #requireEJBReceiver(String, String, String)}, then this method throws an {@link IllegalStateException}
     *
     * @param receiver The {@link EJBReceiver} for which the {@link EJBReceiverContext} is being requested
     * @return The {@link EJBReceiverContext}
     * @throws IllegalStateException If the passed <code>receiver</code> hasn't been registered with this {@link EJBClientContext}
     */
    EJBReceiverContext requireEJBReceiverContext(final EJBReceiver<?> receiver) throws IllegalStateException {
        synchronized (this.ejbReceiverAssociations) {
            final EJBReceiverContext receiverContext = this.ejbReceiverAssociations.get(receiver);
            if (receiverContext == null) {
                throw new IllegalStateException(receiver + " has not been associated with " + this);
            }
            return receiverContext;
        }
    }

    EJBReceiver requireNodeEJBReceiver(final String nodeName) {
        if (nodeName == null) {
            throw new IllegalArgumentException("Node name cannot be null");
        }
        synchronized (this.ejbReceiverAssociations) {
            for (final EJBReceiver<?> ejbReceiver : this.ejbReceiverAssociations.keySet()) {
                if (nodeName.equals(ejbReceiver.getNodeName())) {
                    return ejbReceiver;
                }
            }
        }
        throw new IllegalStateException("No EJBReceiver available for node name " + nodeName);
    }

    EJBReceiverContext requireNodeEJBReceiverContext(final String nodeName) {
        final EJBReceiver ejbReceiver = this.requireNodeEJBReceiver(nodeName);
        return this.requireEJBReceiverContext(ejbReceiver);
    }
}
