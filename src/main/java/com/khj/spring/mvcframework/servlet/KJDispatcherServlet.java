package com.khj.spring.mvcframework.servlet;

import com.khj.spring.mvcframework.servlet.annotation.GPAutowired;
import com.khj.spring.mvcframework.servlet.annotation.GPController;
import com.khj.spring.mvcframework.servlet.annotation.GPRequestMapping;
import com.khj.spring.mvcframework.servlet.annotation.GPService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

public class KJDispatcherServlet extends HttpServlet {

    // 加载主配置文件application.properties中的内容
    private Properties contextConfig = new Properties();

    // 存放扫描到的所有.class文件的类名
    List<String> classNames = new ArrayList<String>();

    // ioc容器, ioc是实际用的
    private Map<String, Object> ioc = new HashMap<String, Object>();

    // 保存URL（接口访问路径：/user/login）
    private Map<String, Method> handlerMapping = new HashMap<String, Method>();


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
        // 绝对路径
        StringBuffer bUrl = req.getRequestURL();
        String url = bUrl.toString();
        // 处理成相对路径
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");

        if (!this.handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 Not Round! + " + url);
            return;
        }

        Method method = handlerMapping.get(url);
        // 获取给方法所在类名称
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        // 获取方法参数，这里写死
        Map<String, String[]> params = req.getParameterMap();
        method.invoke(ioc.get(beanName), new Object[]{req, resp, params.get("name")[0]});
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
     */
    private void initHandlerMapping() {
        if (ioc.isEmpty()) {return;}

        for (Map.Entry<String, Object> entry: ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)) {return;}

            // 获取@RequestMapping("/hello")
            String beanUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                beanUrl = requestMapping.value();   // "/hello"
            }

            // 默认获取该类所有的public方法
            for (Method method : clazz.getMethods()) {
                GPRequestMapping methodRequestMapping = method.getAnnotation(GPRequestMapping.class);
                String url = ("/" + beanUrl + "/" + methodRequestMapping.value()).replaceAll("/+", "/");
                handlerMapping.put(url, method);
                System.out.println("Method:" + url + method);
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
