/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.language.jxpath;

import javax.naming.Context;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.camel.util.jndi.JndiContext;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version 
 */
public class BeanWithJXPathInjectionTest extends CamelTestSupport {
    private static final transient Logger LOG = LoggerFactory.getLogger(BeanWithJXPathInjectionTest.class);

    @Test
    public void testSendMessage() throws Exception {
        template.sendBody("direct:in", new PersonBean("James", "London"));

        MyBean myBean = context.getRegistry().lookupByNameAndType("myBean", MyBean.class);

        assertEquals("bean foo: " + myBean, "James", myBean.getName());
        assertNotNull("Should pass body as well", myBean.getBody());
    }

    @Test
    public void testSendNullMessage() throws Exception {
        template.sendBody("direct:in", new PersonBean(null, "London"));

        MyBean myBean = context.getRegistry().lookupByNameAndType("myBean", MyBean.class);

        assertEquals("bean foo: " + myBean, null, myBean.getName());
        assertNotNull("Should pass body as well", myBean.getBody());
    }

    @Override
    protected Context createJndiContext() throws Exception {
        JndiContext answer = new JndiContext();
        answer.bind("myBean", new MyBean());
        return answer;
    }

    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                from("direct:in").beanRef("myBean");
            }
        };
    }

    public static final class MyBean {
        private PersonBean body;
        private String name;

        @Override
        public String toString() {
            return "MyBean[foo: " + name + " body: " + body + "]";
        }

        public void read(PersonBean body, @JXPath("in/body/name") String name) {
            this.body = body;
            this.name = name;
            LOG.info("read() method called on " + this);
        }

        public PersonBean getBody() {
            return body;
        }

        public String getName() {
            return name;
        }
    }
}
