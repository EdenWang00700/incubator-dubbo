/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package com.alibaba.dubbo.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.*;
import static org.junit.matchers.JUnitMatchers.*;
import static org.hamcrest.core.IsNot.*;

import java.util.List;

import org.junit.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.config.api.DemoService;
import com.alibaba.dubbo.config.consumer.DemoActionByAnnotation;
import com.alibaba.dubbo.config.consumer.DemoActionBySetter;
import com.alibaba.dubbo.config.provider.impl.DemoServiceImpl;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.support.SimpleRegistryExporter;
import com.alibaba.dubbo.registry.support.SimpleRegistryService;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * ConfigTest
 * 
 * @author william.liangf
 */
public class ConfigTest {
    
    private DemoService refer(String url) {
        ReferenceConfig<DemoService> reference = new ReferenceConfig<DemoService>();
        reference.setApplication(new ApplicationConfig("consumer"));
        reference.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        reference.setInterface(DemoService.class);
        reference.setUrl(url);
        return reference.get();
    }

    @Test
    public void testToString() {
        ReferenceConfig<DemoService> reference = new ReferenceConfig<DemoService>();
        reference.setApplication(new ApplicationConfig("consumer"));
        reference.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        reference.setInterface(DemoService.class);
        reference.setUrl("dubbo://127.0.0.1:20881");
        String str = reference.toString();
        assertTrue(str.startsWith("<dubbo:reference "));
        assertTrue(str.contains(" url=\"dubbo://127.0.0.1:20881\" "));
        assertTrue(str.contains(" interface=\"com.alibaba.dubbo.config.api.DemoService\" "));
        assertTrue(str.endsWith(" />"));
    }
    
    @Test
    public void testMultiProtocol() {
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/multi-protocol.xml");
        ctx.start();
        DemoService demoService = refer("dubbo://127.0.0.1:20881");
        String hello = demoService.sayName("hello");
        assertEquals("say:hello", hello);
        ctx.stop();
        ctx.close();
    }

    @Test
    public void testMultiProtocolDefault() {
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/multi-protocol-default.xml");
        ctx.start();
        DemoService demoService = refer("rmi://127.0.0.1:10991");
        String hello = demoService.sayName("hello");
        assertEquals("say:hello", hello);
        ctx.stop();
        ctx.close();
    }
    
    @Test
    public void testMultiProtocolError() {
        try {
            ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/multi-protocol-error.xml");
            ctx.start();
            ctx.stop();
            ctx.close();
        } catch (BeanCreationException e) {
            assertTrue(e.getMessage().contains("Found multi-protocols"));
        }
    }

    @Test
    public void testMultiProtocolRegister() {
        SimpleRegistryService registryService = new SimpleRegistryService();
        Exporter<RegistryService> exporter = SimpleRegistryExporter.export(4547, registryService);
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/multi-protocol-register.xml");
        ctx.start();
        try {
            List<URL> urls = registryService.getRegistered().get("com.alibaba.dubbo.config.api.DemoService");
            assertNotNull(urls);
            assertEquals(1, urls.size());
            assertEquals("dubbo://" + NetUtils.getLocalHost() + ":20824/com.alibaba.dubbo.config.api.DemoService", urls.get(0).toIdentityString());
        } finally {
            ctx.stop();
            ctx.close();
            exporter.unexport();
        }
    }

    @Test
    public void testMultiRegistry() {
        SimpleRegistryService registryService1 = new SimpleRegistryService();
        Exporter<RegistryService> exporter1 = SimpleRegistryExporter.export(4545, registryService1);
        SimpleRegistryService registryService2 = new SimpleRegistryService();
        Exporter<RegistryService> exporter2 = SimpleRegistryExporter.export(4546, registryService2);
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/multi-registry.xml");
        ctx.start();
        try {
            List<URL> urls1 = registryService1.getRegistered().get("com.alibaba.dubbo.config.api.DemoService");
            assertNull(urls1);
            List<URL> urls2 = registryService2.getRegistered().get("com.alibaba.dubbo.config.api.DemoService");
            assertNotNull(urls2);
            assertEquals(1, urls2.size());
            assertEquals("dubbo://" + NetUtils.getLocalHost() + ":20880/com.alibaba.dubbo.config.api.DemoService", urls2.get(0).toIdentityString());
        } finally {
            ctx.stop();
            ctx.close();
            exporter1.unexport();
            exporter2.unexport();
        }
    }

    @Test
    public void testDelayFixedTime() throws Exception {
        SimpleRegistryService registryService = new SimpleRegistryService();
        Exporter<RegistryService> exporter = SimpleRegistryExporter.export(4548, registryService);
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/delay-fixed-time.xml");
        ctx.start();
        try {
            List<URL> urls = registryService.getRegistered().get("com.alibaba.dubbo.config.api.DemoService");
            assertNull(urls);
            while (urls == null) {
                urls = registryService.getRegistered().get("com.alibaba.dubbo.config.api.DemoService");
                Thread.sleep(10);
            }
            assertNotNull(urls);
            assertEquals(1, urls.size());
            assertEquals("dubbo://" + NetUtils.getLocalHost() + ":20883/com.alibaba.dubbo.config.api.DemoService", urls.get(0).toIdentityString());
        } finally {
            ctx.stop();
            ctx.close();
            exporter.unexport();
        }
    }

    @Test
    public void testDelayOnInitialized() throws Exception {
        SimpleRegistryService registryService = new SimpleRegistryService();
        Exporter<RegistryService> exporter = SimpleRegistryExporter.export(4548, registryService);
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/delay-on-initialized.xml");
        ctx.start();
        try {
            List<URL> urls = registryService.getRegistered().get("com.alibaba.dubbo.config.api.DemoService");
            assertNotNull(urls);
            assertEquals(1, urls.size());
            assertEquals("dubbo://" + NetUtils.getLocalHost() + ":20883/com.alibaba.dubbo.config.api.DemoService", urls.get(0).toIdentityString());
        } finally {
            ctx.stop();
            ctx.close();
            exporter.unexport();
        }
    }
    
    @Test
    public void testRmiTimeout() throws Exception {
        if (System.getProperty("sun.rmi.transport.tcp.responseTimeout") != null) {
            System.setProperty("sun.rmi.transport.tcp.responseTimeout", "");
        }
        ConsumerConfig consumer = new ConsumerConfig();
        consumer.setTimeout(1000);
        assertEquals("1000", System.getProperty("sun.rmi.transport.tcp.responseTimeout"));
        consumer.setTimeout(2000);
        assertEquals("1000", System.getProperty("sun.rmi.transport.tcp.responseTimeout"));
    }

    @Test
    public void testAutowireAndAOP() throws Exception {
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/demo-provider.xml");
        providerContext.start();
        try {
            ClassPathXmlApplicationContext byNameContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/aop-autowire-byname.xml");
            byNameContext.start();
            try {
                DemoActionBySetter demoActionBySetter = (DemoActionBySetter) byNameContext.getBean("demoActionBySetter");
                assertNotNull(demoActionBySetter.getDemoService());
                assertEquals("aop:say:hello", demoActionBySetter.getDemoService().sayName("hello"));
                DemoActionByAnnotation demoActionByAnnotation = (DemoActionByAnnotation) byNameContext.getBean("demoActionByAnnotation");
                assertNotNull(demoActionByAnnotation.getDemoService());
                assertEquals("aop:say:hello", demoActionByAnnotation.getDemoService().sayName("hello"));
            } finally {
                byNameContext.stop();
                byNameContext.close();
            }
            ClassPathXmlApplicationContext byTypeContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/aop-autowire-bytype.xml");
            byTypeContext.start();
            try {
                DemoActionBySetter demoActionBySetter = (DemoActionBySetter) byTypeContext.getBean("demoActionBySetter");
                assertNotNull(demoActionBySetter.getDemoService());
                assertEquals("aop:say:hello", demoActionBySetter.getDemoService().sayName("hello"));
                DemoActionByAnnotation demoActionByAnnotation = (DemoActionByAnnotation) byTypeContext.getBean("demoActionByAnnotation");
                assertNotNull(demoActionByAnnotation.getDemoService());
                assertEquals("aop:say:hello", demoActionByAnnotation.getDemoService().sayName("hello"));
            } finally {
                byTypeContext.stop();
                byTypeContext.close();
            }
        } finally {
            providerContext.stop();
            providerContext.close();
        }
    }
    
    @Test
    public void testAppendFilter() throws Exception {
        ProviderConfig provider = new ProviderConfig();
        provider.setFilter("classloader,monitor");
        ServiceConfig<DemoService> service = new ServiceConfig<DemoService>();
        service.setFilter("accesslog,trace");
        service.setProvider(provider);
        service.setApplication(new ApplicationConfig("provider"));
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setInterface(DemoService.class);
        service.setRef(new DemoServiceImpl());
        try {
            service.export();
            List<URL> urls = service.toUrls();
            assertNotNull(urls);
            assertEquals(1, urls.size());
            assertEquals("classloader,monitor,accesslog,trace", urls.get(0).getParameter("service.filter"));
            
            ConsumerConfig consumer = new ConsumerConfig();
            consumer.setFilter("classloader,monitor");
            ReferenceConfig<DemoService> reference = new ReferenceConfig<DemoService>();
            reference.setFilter("accesslog,trace");
            reference.setConsumer(consumer);
            reference.setApplication(new ApplicationConfig("consumer"));
            reference.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            reference.setInterface(DemoService.class);
            reference.setUrl("dubbo://" + NetUtils.getLocalHost() + ":20880?" + DemoService.class.getName() + "?check=false");
            try {
                reference.get();
                urls = reference.toUrls();
                assertNotNull(urls);
                assertEquals(1, urls.size());
                assertEquals("classloader,monitor,accesslog,trace", urls.get(0).getParameter("reference.filter"));
            } finally {
                reference.destroy();
            }
        } finally {
            service.unexport();
        }
    }
    
    @Test
    public void testInitReference() throws Exception {
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/demo-provider.xml");
        providerContext.start();
        try {
            ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/init-reference.xml");
            ctx.start();
            try {
                DemoService demoService = (DemoService)ctx.getBean("demoService");
                assertEquals("say:world", demoService.sayName("world"));
            } finally {
                ctx.stop();
                ctx.close();
            }
        } finally {
            providerContext.stop();
            providerContext.close();
        }
    }
    
    // DUBBO-147   通过RpcContext可以获得所有尝试过的Invoker
    @Test
    public void test_RpcContext_getInvokers() throws Exception {
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(
                ConfigTest.class.getPackage().getName().replace('.', '/') + "/demo-provider-long-waiting.xml");
        providerContext.start();

        try {
            ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(
                    ConfigTest.class.getPackage().getName().replace('.', '/')
                            + "/init-reference-getInvokers.xml");
            ctx.start();
            try {
                DemoService demoService = (DemoService) ctx.getBean("demoService");
                try {
                    demoService.sayName("Haha");
                    fail();
                } catch (RpcException expected) {
                    assertThat(expected.getMessage(), containsString("Tried 3 times to invoke providers"));
                }

                assertEquals(3, RpcContext.getContext().getInvokers().size());
            } finally {
                ctx.stop();
                ctx.close();
            }
        } finally {
            providerContext.stop();
            providerContext.close();
        }
    }
    
    // BUG: DUBBO-846 2.0.9中，服务方法上的retry="false"设置失效
    @Test
    public void test_retrySettingFail() throws Exception {
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(
                ConfigTest.class.getPackage().getName().replace('.', '/') + "/demo-provider-long-waiting.xml");
        providerContext.start();

        try {
            ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(
                    ConfigTest.class.getPackage().getName().replace('.', '/')
                            + "/init-reference-retry-false.xml");
            ctx.start();
            try {
                DemoService demoService = (DemoService) ctx.getBean("demoService");
                try {
                    demoService.sayName("Haha");
                    fail();
                } catch (RpcException expected) {
                    assertThat(expected.getMessage(), containsString("Tried 1 times to invoke providers"));
                }

                assertEquals(1, RpcContext.getContext().getInvokers().size());
            } finally {
                ctx.stop();
                ctx.close();
            }
        } finally {
            providerContext.stop();
            providerContext.close();
        }
    }
    
    // BUG: DUBBO-146 Dubbo序列化失败（如传输对象没有实现Serialiable接口），Provider端也没有异常输出，Consumer端超时出错
    @Test
    public void test_returnSerializationFail() throws Exception {
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/demo-provider-UnserializableBox.xml");
        providerContext.start();
        try {
            ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/init-reference.xml");
            ctx.start();
            try {
                DemoService demoService = (DemoService)ctx.getBean("demoService");
                try {
                    demoService.getBox();
                    fail();
                } catch (RpcException expected) {
                    assertThat(expected.getMessage(), containsString("must implement java.io.Serializable"));
                    assertThat(expected.getMessage(), not(containsString("timeout")));
                }
            } finally {
                ctx.stop();
                ctx.close();
            }
        } finally {
            providerContext.stop();
            providerContext.close();
        }
    }
    
    @Test
    public void testSystemPropertyOverrideProtocol() throws Exception {
        System.setProperty("dubbo.protocol.port", "20812");
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/override-protocol.xml");
        providerContext.start();
        try {
            ProtocolConfig dubbo = (ProtocolConfig) providerContext.getBean("dubbo");
            assertEquals(20812, dubbo.getPort().intValue());
        } finally {
            providerContext.stop();
            providerContext.close();
            System.setProperty("dubbo.protocol.port", "");
        }
    }

    @Test
    public void testSystemPropertyOverrideMultiProtocol() throws Exception {
        System.setProperty("dubbo.protocol.dubbo.port", "20814");
        System.setProperty("dubbo.protocol.rmi.port", "10914");
        ClassPathXmlApplicationContext providerContext = new ClassPathXmlApplicationContext(ConfigTest.class.getPackage().getName().replace('.', '/') + "/override-multi-protocol.xml");
        providerContext.start();
        try {
            ProtocolConfig dubbo = (ProtocolConfig) providerContext.getBean("dubbo");
            assertEquals(20814, dubbo.getPort().intValue());
            ProtocolConfig rmi = (ProtocolConfig) providerContext.getBean("rmi");
            assertEquals(10914, rmi.getPort().intValue());
        } finally {
            providerContext.stop();
            providerContext.close();
            System.setProperty("dubbo.protocol.dubbo.port", "");
            System.setProperty("dubbo.protocol.rmi.port", "");
        }
    }
}