/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.eventregistry.spring.jms;

import java.lang.reflect.Field;
import java.util.Map;

import javax.jms.MessageListener;

import org.flowable.eventregistry.api.ChannelModelProcessor;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.model.ChannelModel;
import org.flowable.eventregistry.model.JmsInboundChannelModel;
import org.flowable.eventregistry.model.JmsOutboundChannelModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpoint;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * @author Filip Hrisafov
 */
public class JmsChannelDefinitionProcessor implements BeanFactoryAware, ChannelModelProcessor {
    
    public static final String CHANNEL_ID_PREFIX = "org.flowable.eventregistry.jms.ChannelJmsListenerEndpointContainer#";

    /**
     * The bean name of the default {@link JmsListenerContainerFactory}.
     */
    static final String DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_BEAN_NAME = "jmsListenerContainerFactory";

    protected JmsOperations jmsOperations;

    protected JmsListenerEndpointRegistry endpointRegistry;

    protected String containerFactoryBeanName = DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_BEAN_NAME;

    protected JmsListenerContainerFactory<?> containerFactory;

    protected BeanFactory beanFactory;

    protected StringValueResolver embeddedValueResolver;

    @Override
    public boolean canProcess(ChannelModel channelModel) {
        return channelModel instanceof JmsInboundChannelModel || channelModel instanceof JmsOutboundChannelModel;
    }

    @Override
    public void registerChannelModel(ChannelModel channelModel, EventRegistry eventRegistry) {
        if (channelModel instanceof JmsInboundChannelModel) {
            JmsInboundChannelModel jmsChannelDefinition = (JmsInboundChannelModel) channelModel;

            JmsListenerEndpoint endpoint = createJmsListenerEndpoint(channelModel, eventRegistry, jmsChannelDefinition);
            registerEndpoint(endpoint, null);
            
        } else if (channelModel instanceof JmsOutboundChannelModel) {
            processOutboundDefinition((JmsOutboundChannelModel) channelModel);
        }
    }

    protected JmsListenerEndpoint createJmsListenerEndpoint(ChannelModel channelDefinition, EventRegistry eventRegistry,
                    JmsInboundChannelModel jmsChannelDefinition) {
        
        String endpointId = getEndpointId(jmsChannelDefinition);

        SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();

        endpoint.setId(endpointId);
        endpoint.setDestination(resolve(jmsChannelDefinition.getDestination()));

        String selector = jmsChannelDefinition.getSelector();
        if (StringUtils.hasText(selector)) {
            endpoint.setSelector(resolve(selector));
        }

        String subscription = jmsChannelDefinition.getSubscription();
        if (StringUtils.hasText(subscription)) {
            endpoint.setSubscription(resolve(subscription));
        }

        String concurrency = jmsChannelDefinition.getConcurrency();
        if (StringUtils.hasText(concurrency)) {
            endpoint.setConcurrency(resolve(concurrency));
        }

        String channelKey = channelDefinition.getKey();
        endpoint.setMessageListener(createMessageListener(eventRegistry, channelKey));
        return endpoint;
    }

    protected MessageListener createMessageListener(EventRegistry eventRegistry, String channelKey) {
        return new JmsChannelMessageListenerAdapter(eventRegistry, channelKey);
    }

    protected void processOutboundDefinition(JmsOutboundChannelModel channelDefinition) {
        String destination = channelDefinition.getDestination();
        if (channelDefinition.getOutboundEventChannelAdapter() == null && StringUtils.hasText(destination)) {
            channelDefinition.setOutboundEventChannelAdapter(new JmsOperationsOutboundEventChannelAdapter(jmsOperations, destination));
        }
    }

    @Override
    public void unregisterChannelModel(ChannelModel channelModel, EventRegistry eventRegistry) {
        String endpointId = getEndpointId(channelModel);
        // currently it is not possible to unregister a listener container
        // In order not to do a lot of the logic that Spring does we are manually accessing the containers to remove them
        // see https://github.com/spring-projects/spring-framework/issues/24228
        MessageListenerContainer listenerContainer = endpointRegistry.getListenerContainer(endpointId);
        if (listenerContainer != null) {
            listenerContainer.stop();
        }

        if (listenerContainer instanceof DisposableBean) {
            try {
                ((DisposableBean) listenerContainer).destroy();
            } catch (Exception e) {
                throw new RuntimeException("Failed to destroy listener container", e);
            }
        }

        Field listenerContainersField = ReflectionUtils.findField(endpointRegistry.getClass(), "listenerContainers");
        if (listenerContainersField != null) {
            listenerContainersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, MessageListenerContainer> listenerContainers = (Map<String, MessageListenerContainer>) ReflectionUtils.getField(listenerContainersField, endpointRegistry);
            if (listenerContainers != null) {
                listenerContainers.remove(endpointId);
            }
        } else {
            throw new IllegalStateException("Endpoint registry " + endpointRegistry + " does not have listenerContainers field");
        }
    }

    /**
     * Register a new {@link JmsListenerEndpoint} alongside the
     * {@link JmsListenerContainerFactory} to use to create the underlying container.
     * <p>The {@code factory} may be {@code null} if the default factory has to be
     * used for that endpoint.
     */
    protected void registerEndpoint(JmsListenerEndpoint endpoint, JmsListenerContainerFactory<?> factory) {
        Assert.notNull(endpoint, "Endpoint must not be null");
        Assert.hasText(endpoint.getId(), "Endpoint id must be set");

        Assert.state(this.endpointRegistry != null, "No JmsListenerEndpointRegistry set");
        endpointRegistry.registerListenerContainer(endpoint, resolveContainerFactory(endpoint, factory), true);
    }

    protected JmsListenerContainerFactory<?> resolveContainerFactory(JmsListenerEndpoint endpoint, JmsListenerContainerFactory<?> containerFactory) {
        if (containerFactory != null) {
            return containerFactory;
        } else if (this.containerFactory != null) {
            return this.containerFactory;
        } else if (containerFactoryBeanName != null) {
            Assert.state(beanFactory != null, "BeanFactory must be set to obtain container factory by bean name");
            // Consider changing this if live change of the factory is required...
            this.containerFactory = beanFactory.getBean(containerFactoryBeanName, JmsListenerContainerFactory.class);
            return this.containerFactory;
        } else {
            throw new IllegalStateException("Could not resolve the " +
                JmsListenerContainerFactory.class.getSimpleName() + " to use for [" +
                endpoint + "] no factory was given and no default is set.");
        }
    }

    protected String getEndpointId(ChannelModel channelModel) {
        String channelDefinitionKey = channelModel.getKey();
        //TODO multi tenant
        return CHANNEL_ID_PREFIX + channelDefinitionKey;
    }

    protected String resolve(String value) {
        if (embeddedValueResolver != null) {
            return embeddedValueResolver.resolveStringValue(value);
        } else {
            return value;
        }
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
        if (beanFactory instanceof ConfigurableBeanFactory) {
            this.embeddedValueResolver = new EmbeddedValueResolver((ConfigurableBeanFactory) beanFactory);
        }
    }

    public JmsOperations getJmsOperations() {
        return jmsOperations;
    }

    public void setJmsOperations(JmsOperations jmsOperations) {
        this.jmsOperations = jmsOperations;
    }

    public JmsListenerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public void setEndpointRegistry(JmsListenerEndpointRegistry endpointRegistry) {
        this.endpointRegistry = endpointRegistry;
    }

    public String getContainerFactoryBeanName() {
        return containerFactoryBeanName;
    }

    public void setContainerFactoryBeanName(String containerFactoryBeanName) {
        this.containerFactoryBeanName = containerFactoryBeanName;
    }

    public JmsListenerContainerFactory<?> getContainerFactory() {
        return containerFactory;
    }

    public void setContainerFactory(JmsListenerContainerFactory<?> containerFactory) {
        this.containerFactory = containerFactory;
    }

}
