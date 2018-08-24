/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.support.Parameter;

import javax.jws.Oneway;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods and public methods for parsing configuration
 *
 * @export
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;
    private static final int MAX_LENGTH = 200;

    private static final int MAX_PATH_LENGTH = 200;

    //属性格式校验
    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,/\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");
    private static final Map<String, String> legacyProperties = new HashMap<String, String>();
    private static final String[] SUFFIXES = new String[]{"Config", "Bean"};

    static {
        legacyProperties.put("dubbo.protocol.name", "dubbo.service.protocol");
        legacyProperties.put("dubbo.protocol.host", "dubbo.service.server.host");
        legacyProperties.put("dubbo.protocol.port", "dubbo.service.server.port");
        legacyProperties.put("dubbo.protocol.threads", "dubbo.service.max.thread.pool.size");
        legacyProperties.put("dubbo.consumer.timeout", "dubbo.service.invoke.timeout");
        legacyProperties.put("dubbo.consumer.retries", "dubbo.service.max.retry.providers");
        legacyProperties.put("dubbo.consumer.check", "dubbo.service.allow.no.provider");
        legacyProperties.put("dubbo.service.url", "dubbo.service.address");

        // this is only for compatibility
        Runtime.getRuntime().addShutdownHook(DubboShutdownHook.getDubboShutdownHook());
    }

    protected String id;

    private static String convertLegacyValue(String key, String value) {
        if (value != null && value.length() > 0) {
            if ("dubbo.service.max.retry.providers".equals(key)) {
                return String.valueOf(Integer.parseInt(value) - 1);
            } else if ("dubbo.service.allow.no.provider".equals(key)) {
                return String.valueOf(!Boolean.parseBoolean(value));
            }
        }
        return value;
    }

    protected static void appendProperties(AbstractConfig config) {
        if (config == null) {
            return;
        }
        String prefix = "dubbo." + getTagName(config.getClass()) + ".";
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(method.getModifiers()) // 方法是 public 的 setting 方法。
                        && method.getParameterTypes().length == 1 && isPrimitive(method.getParameterTypes()[0])) { // 方法的唯一参数是基本数据类型
                    // 获得属性名，例如 `ApplicationConfig#setName(...)` 方法，对应的属性名为 name 。
                    //把第三个字母转成小写后再从第四个字母找到末尾
                    String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), ".");
                    String value = null;
                    if (config.getId() != null && config.getId().length() > 0) {
                        String pn = prefix + config.getId() + "." + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    if (value == null || value.length() == 0) {
                        String pn = prefix + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    if (value == null || value.length() == 0) {
                        Method getter;
                        try {
                            getter = config.getClass().getMethod("get" + name.substring(3));
                        } catch (NoSuchMethodException e) {
                            try {
                                getter = config.getClass().getMethod("is" + name.substring(3));
                            } catch (NoSuchMethodException e2) {
                                getter = null;
                            }
                        }
                        if (getter != null) {
                            if (getter.invoke(config) == null) {
                                if (config.getId() != null && config.getId().length() > 0) {
                                    value = ConfigUtils.getProperty(prefix + config.getId() + "." + property);
                                }
                                if (value == null || value.length() == 0) {
                                    value = ConfigUtils.getProperty(prefix + property);
                                }
                                if (value == null || value.length() == 0) {
                                    String legacyKey = legacyProperties.get(prefix + property);
                                    if (legacyKey != null && legacyKey.length() > 0) {
                                        value = convertLegacyValue(legacyKey, ConfigUtils.getProperty(legacyKey));
                                    }
                                }

                            }
                        }
                    }
                    if (value != null && value.length() > 0) {
                        method.invoke(config, convertPrimitive(method.getParameterTypes()[0], value));
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 读取环境变量和 properties 配置到配置对象, 从配置文件中set各个属性的值到对象
     *
     * 参见：<a href="https://dubbo.gitbooks.io/dubbo-user-book/configuration/properties.html">属性配置</a>
     *
     * @param config 配置对象
     */
    public static void appendProperties1(AbstractConfig config){
        if (config == null) {
            return;
        }

        String perfix = "dubbo." + getTagName(config.getClass()) + ".";
        //获得所有的非静态方法
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 1 && isPrimitive(method.getParameterTypes()[0])) {
                    // 获得属性名，例如 `ApplicationConfig#setName(...)` 方法，对应的属性名为 name
                    String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), ".");
                    String value = null;
                    // 【环境变量】优先从带有 `Config#id` 的配置中获取，例如：`dubbo.application.demo-provider.name` 。
                    if (config.getId() != null && config.getId().length() > 0) {
                        String pn = perfix + config.getId() + "." + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(pn)) {
                            logger.info("Use System Property" + pn + "to config dubbo");
                        }
                    }
                    // 【环境变量】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name` 。
                    if (value == null || value.length() == 0) {
                        String pn = perfix + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(pn)) {
                            logger.info("Use System property" + pn + "to config dubbo");
                        }
                    }

                    if (value == null || value.length() == 0) {
                        // 覆盖优先级为：环境变量 > XML 配置 > properties 配置，因此需要使用 getter 判断 XML 是否已经设置
                        Method getter;
                        try {
                            //找该配置的get方法
                            getter = config.getClass().getMethod("get" + name.substring(3), new Class<?>[0]);
                        } catch (NoSuchMethodException e) {
                            try {
                                //找配置的is方法
                                getter = config.getClass().getMethod("is" + name.substring(3), new Class<?>[0]);
                            } catch (NoSuchMethodException e1) {
                                getter = null;
                            }
                        }
                        if (getter != null) {
                            //现在getter已经是方法名了
                            // 使用 getter 判断 XML 是否已经设置,
                            //invoke方法第一个参数指明的是invoke方法作用的对象, 第二个参数指明的这个方法第一个参数的值, 用new Object[0]就是说这个方法没有参数
                            if (getter.invoke(config, new Object[0] )== null) {  //如果方法执行为null,就去配置文件中取值
                                // 【properties 配置】优先从带有 `Config#id` 的配置中获取，例如：`dubbo.application.demo-provider.name`
                                value = ConfigUtils.getProperty(perfix + config.getId() + "." + property); //从配置文件中取对应的值
                            }
                            // 【properties 配置】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name`
                            if (value == null || value.length() == 0) {
                                value = ConfigUtils.getProperty(perfix + property);
                            }
                            // 【properties 配置】老版本兼容，获取不到，最后不带 `Config#id` 的配置中获取，例如：`dubbo.protocol.name`
                            if (value == null || value.length() == 0) {
                                String legacyKey = legacyProperties.get(perfix + property);
                                if (legacyKey != null && legacyKey.length() != 0) {
                                    value = convertLegacyValue(legacyKey, ConfigUtils.getProperty(legacyKey));
                                }
                            }

                        }
                    }

                    //因为最上层的陈获得的是set方法,所以在这里执行方法,把setter值set到对象中
                    if (value != null && value.length() > 0) {

                        //执行这个方法, 第一个参数是执行这个方法的对象,第二个参数这个方法需要的参数, 而convertPrimitive方法是把这个方法的参数进行处理,把String变成预期的对象
                        method.invoke(config, new Object[]{convertPrimitive(method.getParameterTypes()[0], value)});

                    }

                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

        }

    }

    /**
     * 将配置对象的属性，添加到参数集合, 从config中key的str 放到parameter中
     *
     * @param parameters 参数集合
     * @param config 配置对象
     * @param prefix 属性前缀
     */
    protected static void appendParameters1(Map<String, String> parameters, Object config, String prefix){
        if (config == null) {
            return ;
        }

        Method[] methods = config.getClass().getMethods();
        // 方法为获取基本类型，public 的 getting 方法。
        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is")
                        && !"getClass".equals(name)
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType()))
                        && Modifier.isPublic(method.getModifiers())) {
                    //获得Parameter注解,后面处理
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    //忽略的情况
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                        continue;
                    }
                    //获得属性名,当方法名以get开始,int = 3, 当方法名以is开始,int = 2
                    int i = name.startsWith("get") ? 3 : 2;
                    //获得属性名, pro就是属性名,对于有key()的 key的值就是属性名,没有的get后面的名字
                    String prop = StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase(), ".");
                    String key;
                    if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }

                    //获得属性值 str表示属性的值,key表示属性的key, 执行get方法,得到str, 执行get方法,实际上就是从配置的文件中读到对应的值
                    Object value = method.invoke(config, new Object[0]);

                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        if (parameter != null && parameter.escaped()) {
                            //转义, 使用UTF-8 编码
                            str = URL.encode(str);
                        }

                        //拼接, 详细说明参见 `Parameter#append()` 方法的说明
                        if (parameter != null && parameter.append()) {
                            // default. 里获取，适用于 ServiceConfig =》ProviderConfig 、ReferenceConfig =》ConsumerConfig 。
                            String pre = parameters.get(Constants.DEFAULT_KEY + "." + key); //首先先把default的str加上
                            if (pre != null && pre.length() > 0) {
                                //拼接后的结果
                                str = pre + "," + str;
                            }

                            // 通过 `parameters` 属性配置，例如 `AbstractMethodConfig.parameters`
                            //从parameter中获取到后再拼接上
                            //再加上非default的str
                            pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }

                        //对key进行处理
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }

                        /**
                         * 这个是这个方法最关键,最核心的功能, 就是从parameter的map中取出对应的key的value, 如果这个value已经有值了, 那么就把新的值拼接上去, 如果没有值
                         * 就把key,value,put进去, 既 添加配置项到 parameters
                         *
                         * 疑问: 这个key是一样的, 那么如果key相同会覆盖还是会怎么样呢?
                         *
                         * 查了资料,是会覆盖的
                         */
                        parameters.put(key, str);
                    } else if (parameter != null && parameter.required()) {
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + "== null");
                    }
                    //通过 #getParameters() 对应的属性，动态设置配置项，拓展出非 Dubbo 内置好的逻辑。
                } else if ("getParameter".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace("-", "."), entry.getValue());
                        }
                    }
                }

            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }
    }
    /**
     * 将带有 @Parameter(attribute = true) 配置对象的属性，添加到参数集合, 从config中屈指,放到parameter中
     * 主要用于事件通知
     * @param parameters 参数集合
     * @param config 配置对象
     * @param prefix 前缀
     */
    public static void appendAttributes1(Map<Object, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }

        Method[] methods = config.getClass().getMethods();

        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is"))
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    if (parameter == null || !parameter.attribute()) {
                        continue;
                    }
                    //得到属性的名字,如果有key()就用key()作为parameters中对象的key, 如果没有key()就用方法名字作为key
                    String key;
                    if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        int i = name.startsWith("get") ? 3 : 2;
                        key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                    }

                    //获得属性值,如果存在就添加到parameters集合中, 执行这个get方法或is方法,得到值
                    Object value = method.invoke(config, new Object[0]);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }

            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }


    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) {
            if (tag.endsWith(suffix)) {
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        tag = tag.toLowerCase();
        return tag;
    }

    protected static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    protected static void appParameters1(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        //把config的各个属性都放到map中
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();

                if ((name.startsWith("get") || name.startsWith("is")) && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                        Parameter parameter = method.getAnnotation(Parameter.class);
                    if ( method.getReturnType() == Object.class || (parameter != null && parameter.excluded())) {
                        continue;
                    }

                    int i = name.startsWith("get") ? 3 : 2;
                    String property = StringUtils.camelToSplitName(name.substring( i , i+1).toLowerCase() + name.substring(i+1), ".");
                    //key代表参数是的关键字
                    String key;
                    if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = property;
                    }

                    //通过执行getKey的方法得到value
                    Object value = method.invoke(config, new Object[0]);

                    String  str = String.valueOf(value).trim();

                    //这是得到以key为键的值, 并增加进去该配置文件的新的value, 并以","分隔.
                    if (value != null && str.length()>0) {
                        //转义 - 可能后面是要把map对象的每个key和value都拼到URL上, 所以在这里需要对str进行UTF-8编码
                        if (parameter != null && str.length() > 0) {
                            //把String变成utf-8编码
                            str = URL.encode(str);
                        }
                        // 拼接，详细说明参见 `Parameter#append()` 方法的说明。
                        if (parameter != null && parameter.append()) {

                            //得到之前相同的key已经put进去的value, 在后面把新的value拼接上去.
                            String pre = parameters.get(Constants.DEFAULT_KEY + "." + key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }

                            // 通过 `parameters` 属性配置，例如 `AbstractMethodConfig.parameters` 。
                            pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }

                        //profix是传进来的前缀, 这个是通过prefix + key的方式得到这个配置文件自己的键值对, 区分之前键值对的继承关系
                        if (prefix != null && prefix.length()>0) {
                            key = prefix + key;
                        }

                        //如果是必须的, 而value又等于null 那么就抛异常, 对应 if (value != null && str.length()>0)
                    } else if (parameter != null && parameter.required()) {
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                //对应判断方法名字最上面的if
                } else if ( "getParameters".equals(name) && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0 && method.getReturnType() == Map.class) {
                    Map<String, String> map = ( Map<String, String> )method.invoke(config, new Object[0]);
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length()>0) ? prefix + "." : "" ;
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace("-", "."), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static void appAttributes(Map<Object, Object> parameters, Object config, String prefix) {
        if ( config == null ) {
            return;
        }

        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is")) && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers()) && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    Parameter parameter = method.getAnnotation(Parameter.class);

                    if (parameter == null || !parameter.attribute()) {
                        continue;
                    }

                    String key;

                    if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        int i = name.startsWith("get") ? 3 : 2;
                        key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                    }

                    Object value = method.invoke(config, new Object[0]);

                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }

                        parameters.put(key, value);
                    }
                }

            } catch (Exception e) {
                new IllegalStateException(e.getMessage(), e);
            }
        }
    }




    @SuppressWarnings("unchecked")
    protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is"))
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                        continue;
                    }
                    int i = name.startsWith("get") ? 3 : 2;
                    String prop = StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
                    String key;
                    if (parameter != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }
                    Object value = method.invoke(config);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        if (parameter != null && parameter.escaped()) {
                            str = URL.encode(str);
                        }
                        if (parameter != null && parameter.append()) {
                            String pre = parameters.get(Constants.DEFAULT_KEY + "." + key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                            pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, str);
                    } else if (parameter != null && parameter.required()) {
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                } else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static void appendAttributes(Map<Object, Object> parameters, Object config) {
        appendAttributes(parameters, config, null);
    }

    protected static void appendProperties2(AbstractConfig config) throws InvocationTargetException, IllegalAccessException {
        if (config == null) {
            return;
        }

        String prefix = "dubbo." + getTagName(config.getClass()) + ".";

        Method[ ] methods = config.getClass().getMethods();

        for (Method method : methods) {
            String name = method.getName();
            if ( name.length() > 0 && name.startsWith("set") && Modifier.isPublic(method.getModifiers())
                    && method.getParameterTypes().length == 1 && isPrimitive(method.getParameterTypes()[0])) {
                //举例将protocolName转化为protocol.name
                String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase()
                        + name.substring(4), ".");
                String value = null;
                //id 属性，配置对象的编号，适用于除了 API 配置之外的三种配置方式，标记一个配置对象，可用于对象之间的引用。
                // 例如 XML 的 <dubbo:service provider="${PROVIDER_ID}"> ，其中 provider 为 <dubbo:provider> 的 ID 属性。
                // 这个ID指的是config的Id, 不是config中某个配置的id属性
                if (config.getId() != null && config.getId().length() > 0) {
                    String propertyName = prefix + config.getId() + "." + property;
                    //从系统启动参数中从拿这个属性
                    value = System.getProperty(propertyName);
                    if (!StringUtils.isBlank(value)) {
                        logger.info("user system proterty" + propertyName + "to config dubbo");
                    }
                }

                //
                //【环境变量】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name`
                if (value == null && value.length() > 0) {
                    String propertyName = prefix + property;
                    value = System.getProperty(propertyName);
                    if (!StringUtils.isBlank(value)) {
                        logger.info("Use System Property " + propertyName + " to config dubbo");
                    }
                }

                if (value == null || value.length() == 0) {
                    // 覆盖优先级为：环境变量 > XML 配置 > properties 配置，因此需要使用 getter 判断 XML 是否已经设置
                    Method getter;
                    try {
                        getter = config.getClass().getMethod("get" + name.substring(3), new Class[0]);


                    } catch (NoSuchMethodException e) {
                        getter = null;
                    }

                    if (getter != null) {
                        if (getter. invoke(config, new Object[0]) == null) {
                            if (config.getId() != null && config.getId().length() > 0) {
                                value = ConfigUtils.getProperty(prefix + config.getId() + "." + property);
                            }

                            if (value == null || value.length() == 0) {
                                value = ConfigUtils.getProperty(prefix + property);
                            }

                        }
                    }
                }

                if (value != null && value.length() > 0) {
                    method.invoke(config, new Object[]{convertPrimitive(method.getParameterTypes()[0], value)});
                }
            }
        }
    }


    protected static void appendAttributes(Map<Object, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is"))
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    if (parameter == null || !parameter.attribute())
                        continue;
                    String key;
                    if (parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        int i = name.startsWith("get") ? 3 : 2;
                        key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                    }
                    Object value = method.invoke(config);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    private static boolean isPrimitive(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Character.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Object.class;
    }

    private static Object convertPrimitive(Class<?> type, String value) {
        if (type == char.class || type == Character.class) {
            return value.length() > 0 ? value.charAt(0) : '\0';
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value);
        } else if (type == byte.class || type == Byte.class) {
            return Byte.valueOf(value);
        } else if (type == short.class || type == Short.class) {
            return Short.valueOf(value);
        } else if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        } else if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        } else if (type == float.class || type == Float.class) {
            return Float.valueOf(value);
        } else if (type == double.class || type == Double.class) {
            return Double.valueOf(value);
        }
        return value;
    }

    protected static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (value != null && value.length() > 0
                && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (value != null && value.length() > 0) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(Constants.REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (Constants.DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException("No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    protected static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    protected static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    protected static void checkName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    protected static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    protected static void checkParameterName(Map<String, String> parameters) {
        if (parameters == null || parameters.size() == 0) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    protected static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" contains illegal " +
                        "character, only digit, letter, '-', '_' or '.' is legal.");
            }
        }
    }

    @Parameter(excluded = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class
                    && method.getReturnType() != void.class
                    && method.getParameterTypes().length == 0
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                try {
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
                    Object value = method.invoke(annotation);
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, parameterType);
                            setterMethod.invoke(this, value);
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public String toString() {
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            buf.append(getTagName(getClass()));
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                try {
                    String name = method.getName();
                    if ((name.startsWith("get") || name.startsWith("is"))
                            && !"getClass".equals(name) && !"get".equals(name) && !"is".equals(name)
                            && Modifier.isPublic(method.getModifiers())
                            && method.getParameterTypes().length == 0
                            && isPrimitive(method.getReturnType())) {
                        int i = name.startsWith("get") ? 3 : 2;
                        String key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                        Object value = method.invoke(this);
                        if (value != null) {
                            buf.append(" ");
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(value);
                            buf.append("\"");
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

}
