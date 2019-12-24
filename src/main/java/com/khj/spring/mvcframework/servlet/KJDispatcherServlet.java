package com.khj.spring.mvcframework.servlet;

import com.khj.spring.mvcframework.servlet.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KJDispatcherServlet extends HttpServlet {

    // 加载主配置文件application.properties中的内容
    private Properties contextConfig = new Properties();

    // 存放扫描到的所有.class文件的类名
    List<String> classNames = new ArrayList<String>();

    // ioc容器, ioc是实际用的
    private Map<String, Object> ioc = new HashMap<String, Object>();

//    // 保存URL（接口访问路径：/user/login）
//    private Map<String, Method> handlerMapping = new HashMap<String, Method>();

    // 映射URL和method
    private List<Handler> handlerMapping = new ArrayList<Handler>();


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        super.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {


        // 6、调用、运行阶段
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exection, Detail：" + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception{

        try {
            Handler handler = getHandler(req);

            if (handler == null) {
                resp.getWriter().write("404 Not found!");
            }

            //获取方法参数列表
            Class<?>[] paramTypes = handler.method.getParameterTypes();

            //保存所有需要自动赋值的参数值
            Object [] paramValues = new Object[paramTypes.length];

            Map<String,String[]> params = req.getParameterMap();
            for (Map.Entry<String, String[]> param : params.entrySet()) {
                String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");
                System.out.println(value);

                //如果找到匹配的对象，则开始填充参数值
                if(!handler.paramIndexMapping.containsKey(param.getKey())){continue;}
                int index = handler.paramIndexMapping.get(param.getKey());
                paramValues[index] = convert(paramTypes[index],value);
            }


            //设置方法中的request和response对象
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;

            handler.method.invoke(handler.controller, paramValues);


        }catch(Exception e){
            throw e;
        }
    }

    /**
     * 初始化阶段
     * @throws ServletException
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        //1.加载配置文件
        doloadConfig(config.getInitParameter("contextConfigLocation"));

        //2.扫描相关类
        doScanner(contextConfig.getProperty("scanPackage"));

        //3.初始化扫描的类，并放入IOC容器中
        doInstance();

        //4、完成依赖注入
        doAutowired();

        //5、初始化HandlerMapping
        initHandlerMapping();


        System.out.println("Spring framework is init;");
    }

    /**
     * 5、初始化HandlerMapping
     *      映射url与method的关系
     */
    private void initHandlerMapping() {
        if (ioc.isEmpty()) {return;}

        for (Map.Entry<String, Object> entry: ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)) {return;}

            // 获取@RequestMapping("/user")
            String beanUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                beanUrl = requestMapping.value();   // "/user"
            }

            // 默认获取该类所有的public方法
            for (Method method : clazz.getMethods()) {
                // 没有@*Mapping注解的直接过滤
                if (method.isAnnotationPresent(GPRequestMapping.class)) {continue;}

                // 映射url和method
                GPRequestMapping methodRequestMapping = method.getAnnotation(GPRequestMapping.class);
                String url = ("/" + beanUrl + "/" + methodRequestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(url);
                handlerMapping.add(new Handler(clazz.getName(), method, pattern));

                System.out.println("mapping:" + url + method);
            }
        }
    }

    //url传过来的参数都是String类型的，HTTP是基于字符串协议
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        //这时候，我们应该想到策略模式了
        //在这里暂时不实现，希望小伙伴自己来实现
        return value;
    }

    private Handler getHandler(HttpServletRequest req) throws Exception {
        // 绝对路径
        StringBuffer bUrl = req.getRequestURL();
        String url = bUrl.toString();
        // 处理成相对路径
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");

        for (Handler handler : handlerMapping) {
            try {
                Matcher matcher = handler.pattern.matcher(url);
                if (!matcher.matches()) {
                    continue;
                }
                return handler;
            } catch (Exception e) {
                throw e;
            }
        }
        return null;
    }

    private class Handler {

        private String controller;  // method对应的实例
        private Method method;      // 映射的方法
        private Pattern pattern;
        private Map<String, Integer> paramIndexMapping; //method的参数顺序

        public Handler(String controller, Method method, Pattern pattern) {
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;

            paramIndexMapping = new HashMap<String, Integer>();
            putParamIndexMapping(method);
        }

        /**
         * 将method 中的参数+有序放入map
         * @param method
         */
        private void putParamIndexMapping(Method method) {
            // 提取参数
            Annotation[][] annotations = method.getParameterAnnotations();
            for (int i = 0; i < annotations.length; i++) {
                for (Annotation a : annotations[i]) {
                    if (a instanceof GPRequestMapping) {
                        String paramName = ((GPRequestMapping) a).value();
                        if (!"".equals(paramName)) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            // 提取request和response
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> paramType = paramTypes[i];
                if (paramType == HttpServletResponse.class || paramType == HttpServletRequest.class) {
                    paramIndexMapping.put(paramType.getName(), i);
                }
            }
        }
    }




    /**
     * 4、完成依赖注入
     */
    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {

            // getDeclaredFields() 获取所有的字段，包括private/protechted/default...
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                // 只获取Autowired的
                if (!field.isAnnotationPresent(GPAutowired.class)) {continue;}
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);
                String beanName = "".equals(autowired.value()) ? field.getType().getName() : autowired.value().trim();

                // 如果field是public以外的修饰符，暴力访问
                field.setAccessible(true);

                // 用反射机制，动态给字段复制
                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 3.初始化扫描的类，并放入IOC容器中
     */
    private void doInstance() {
        // 初始化，为DI做准备
        if (classNames.isEmpty()) {return;}

        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);

                // 什么样的类要初始化？
                // 加了注解的类才初始化
                // 这里只举例@Controller、@Service
                if (clazz.isAnnotationPresent(GPController.class)) {
                    Object instance = clazz.newInstance();
                    // key : 类名 首字母小写
                    String key = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(key, instance);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    // 1、默认类名首字母一定是大写
                    // 2、自定义的beanName : @Service("userService")    serivce.value() = "userService"
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = "".equals(service.value()) ? toLowerFirstCase(clazz.getSimpleName()) : service.value();


                    // 3、根据类型自动复制

                } else {continue;}


            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InstantiationException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * String转换，首字母小写
     * @param simpleName    该字符串一定是驼峰命名规则，首字母一定是大写
     * @return
     */
    private String toLowerFirstCase(String simpleName) {
        char [] chars = simpleName.toCharArray();
        //+32  大小写字母的ASCII码相差32
        chars[0] += 32;
        return chars.toString();
    }

    /**
     * 2.扫描相关类
     * @param scanPackage
     */
    private void doScanner(String scanPackage) {
        // scanPackage = com.khj.spring.demo
        // 转换为文件路径，=== 把com.khj.spring.demo改为com/khj/spring/demo
        //classpath
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classpath = new File(url.getFile());
        for (File file : classpath.listFiles()) {
            if (file.isDirectory()) {
                doScanner(scanPackage+"."+ file.getName());
            } else {
                if (!file.getName().endsWith(".class")) {continue;}
                String className = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(className);
            }
        }

    }

    /**
     * 1. 加载配置
     * @param contextConfigLocation     spring主配置文件的classpath
     */
    private void doloadConfig(String contextConfigLocation) {

        //直接从类路径下找到Spring住配置文件的路径
        //将其读取到Properties对象中
        //相当于把application.properties文件中的信息保存到内存中
        InputStream fis  = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);

        try {
            // contextConfig Properties对象
            contextConfig.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }


    }
}
