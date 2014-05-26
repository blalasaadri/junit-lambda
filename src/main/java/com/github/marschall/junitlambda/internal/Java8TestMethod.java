package com.github.marschall.junitlambda.internal;

import com.github.marschall.junitlambda.annotations.ParameterRecord;
import com.github.marschall.junitlambda.annotations.ParameterRecords;
import junitparams.FileParameters;
import junitparams.Parameters;
import junitparams.internal.TestMethod;
import junitparams.mappers.DataMapper;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.TestClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.lang.model.type.NullType;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TODO AC: JavaDoc
 *
 * @author Alasdair Collinson
 */
public class Java8TestMethod extends TestMethod {

    private static final Logger LOG = LoggerFactory.getLogger(Java8TestMethod.class);

    private Parameters parametersAnnotation;
    private FileParameters fileParametersAnnotation;
    private ParameterRecords parameterRecordsAnnotation;
    private Class<?> testClass;
    private List<Object> params;

    public Java8TestMethod(FrameworkMethod method, TestClass testClass) {
        super(method, testClass);
        parametersAnnotation = method.getAnnotation(Parameters.class);
        fileParametersAnnotation = method.getAnnotation(FileParameters.class);
        parameterRecordsAnnotation = method.getAnnotation(ParameterRecords.class);
        this.testClass = testClass.getJavaClass();
    }

    public static List<TestMethod> listFrom(List<FrameworkMethod> annotatedMethods, TestClass testClass) {
        return annotatedMethods.stream().map(frameworkMethod -> new Java8TestMethod(frameworkMethod, testClass)).collect(Collectors.toList());
    }

    private static <T> List<T> toList(T... array) {
        return Stream.of(array).collect(Collectors.toList());
    }

    public Object[] parametersSets() {
        if (params != null)
            return params.toArray();

        if (parametersAnnotation != null || parameterRecordsAnnotation != null) {
            // get the parameters from the {@link Parameters#value()} element
            params = paramsFromAnnotation();

            // add the parameters from source files
            params.addAll(paramsFromSource());
            // and now add the parameters from "parametersFor"-methods
            params.addAll(paramsFromMethod(testClass));
            if (params.isEmpty()) {
                throw new RuntimeException("Could not find parameters for " + frameworkMethod() + " so no params were used.");
            }
        }
        if (fileParametersAnnotation != null) {
            params.addAll(paramsFromFile());
        }


        if (params != null) {
            return params.toArray();
        } else {
            return new Object[]{};
        }
    }

    private List<Object> paramsFromAnnotation() {
        List<Object> result = toList();
        if(parametersAnnotation != null) {
            result.addAll(toList(parametersAnnotation.value()));
        }
        if(parameterRecordsAnnotation != null) {
            // The values here are supposed to be added as one value rather than as separate ones.
            result.addAll(Arrays.stream(parameterRecordsAnnotation.value()).map(annotation -> annotation.value()).collect(Collectors.toList()));
        }
        return result;
    }

    private List<Object> paramsFromFile() {
        try (Reader reader = createProperReader()) {
            DataMapper mapper = fileParametersAnnotation.mapper().newInstance();
            return toList(mapper.map(reader));
        } catch (IOException | IllegalAccessException | InstantiationException e) {
            LOG.error("Error while retrieving parameters from file", e);
            throw new RuntimeException("Could not successfully read parameters from file: " + fileParametersAnnotation.value(), e);
        }
    }

    private Reader createProperReader() throws IOException {
        String filepath = fileParametersAnnotation.value();

        if (filepath.indexOf(':') < 0) {
            return new FileReader(filepath);
        }
        String protocol = filepath.substring(0, filepath.indexOf(':'));
        String filename = filepath.substring(filepath.indexOf(':') + 1);

        if ("classpath".equals(protocol)) {
            return new InputStreamReader(getClass().getClassLoader().getResourceAsStream(filename));
        } else if ("file".equals(protocol)) {
            return new FileReader(filename);
        }

        throw new IllegalArgumentException("Unknown file access protocol. Only 'file' and 'classpath' are supported!");
    }

    /**
     * TODO AC: JavaDoc
     *
     * @return
     */
    private List<Object> paramsFromSource() {
        // if the source class is undefined
        boolean noSourceGiven = false;
        if(parametersAnnotation == null && parameterRecordsAnnotation == null) {
            noSourceGiven = true;
        } else if(parametersAnnotation != null && parametersAnnotation.source().isAssignableFrom(NullType.class)) {
            noSourceGiven = true;
        } else {
            boolean nullType = true;
            for(ParameterRecord parameterRecord : parameterRecordsAnnotation.value()) {
                if(!parameterRecord.source().isAssignableFrom(NullType.class)) {
                    nullType = false;
                    break;
                }
            }
            if(nullType) {
                noSourceGiven = true;
            }
        }
        if(noSourceGiven) {
            return toList();
        }

        List<Class<?>> sourceClasses = new ArrayList<>();
        List<String> methods = new ArrayList<>();

        if (parametersAnnotation != null) {
            sourceClasses.add(parametersAnnotation.source());
            methods.add(parametersAnnotation.method());
        }
        if (parameterRecordsAnnotation != null) {
            Stream<ParameterRecord> parameterRecordStream = Arrays.asList(parameterRecordsAnnotation.value()).stream();
            sourceClasses.addAll(parameterRecordStream.map(ParameterRecord::source).collect(Collectors.toList()));
            methods.addAll(parameterRecordStream.map(ParameterRecord::method).collect(Collectors.toList()));
        }

        List<Object> params = toList();
        for (Class<?> sourceClass : sourceClasses) {
            params.addAll(fillResultWithAllParamProviderMethods(sourceClass));
            params.addAll(paramsFromMethod(sourceClass));
        }
        return params;
    }

    private List<Object> paramsFromMethod(Class<?> classWithMethod) {
        List<String> methodAnnotations = toList();
        if(parametersAnnotation != null) {
            methodAnnotations.addAll(toList(parametersAnnotation.method()));
        }
        /*if(parameterRecordsAnnotation != null) {
            // TODO AC: Explain
            methodAnnotations.addAll(Arrays.asList(parameterRecordsAnnotation.value()).stream().map(ParameterRecord::method).collect(Collectors.toList()));
        }*/

        List<Object> result = toList();
        for(String methodAnnotation : methodAnnotations) {
            if (methodAnnotation.isEmpty()) {
                result.addAll(invokeMethodWithParams(defaultMethodName(), classWithMethod));
                break;
            }

            Stream<List<Object>> invokedMethods = Stream.of(methodAnnotation.split(",")).
                    map(methodName -> invokeMethodWithParams(methodName.trim(), classWithMethod));
            Optional<List<Object>> combinedMethods = invokedMethods.reduce((list1, list2) -> {
                list1.addAll(list2);
                return list1;
            });
            if (combinedMethods.isPresent()) {
                result.addAll(combinedMethods.get());
            }
        }

        return result;
    }

    private String defaultMethodName() {
        String methodName;
        methodName = "parametersFor" + frameworkMethod().getName().substring(0, 1).toUpperCase()
                + frameworkMethod().getName().substring(1);
        return methodName;
    }

    private List<Object> invokeMethodWithParams(String methodName, Class<?> testClass) {
        Method provideMethod = findParamsProvidingMethodInTestclassHierarchy(methodName, testClass);

        return invokeParamsProvidingMethod(testClass, provideMethod);
    }

    private Method findParamsProvidingMethodInTestclassHierarchy(String methodName, Class<?> testClass) {
        Method provideMethod = null;
        Class<?> declaringClass = testClass;
        while (declaringClass.getSuperclass() != null) {
            try {
                provideMethod = declaringClass.getDeclaredMethod(methodName);
                break;
            } catch (NoSuchMethodException e) {
                LOG.debug("No method named \"" + methodName + "\" could be found");
            }
            declaringClass = declaringClass.getSuperclass();
        }
        return provideMethod;
    }

    @SuppressWarnings("unchecked")
    private List<Object> invokeParamsProvidingMethod(Class<?> testClass, Method provideMethod) {
        if (provideMethod == null) {
            return toList();
        }
        try {
            Object testObject = testClass.newInstance();
            provideMethod.setAccessible(true);
            Object invocationResult = provideMethod.invoke(testObject);
            try {
                List<Object> params = toList((Object[]) invocationResult);
                return encapsulateParamsIntoArrayIfSingleParamsetPassed(params);
            } catch (ClassCastException e) {
                // Iterable
                try {
                    ArrayList<Object[]> res = new ArrayList<>();
                    for (Object[] paramSet : (Iterable<Object[]>) invocationResult)
                        res.add(paramSet);
                    return toList(res.toArray());
                } catch (ClassCastException e1) {
                    // Iterable with consecutive paramsets, each of one param
                    ArrayList<Object> res = new ArrayList<>();
                    for (Object param : (Iterable<?>) invocationResult)
                        res.add(new Object[]{param});
                    return res;
                }
            }
        } catch (ClassCastException e) {
            throw new RuntimeException("The return type of: " + provideMethod.getName() + " defined in class " + testClass
                    + " is not Object[][] nor Iterable<Object[]>. Fix it!", e);
        } catch (Exception e) {
            throw new RuntimeException("Could not invoke method: " + provideMethod.getName() + " defined in class " + testClass
                    + " so no params were used.", e);
        }
    }

    private List<Object> encapsulateParamsIntoArrayIfSingleParamsetPassed(List<Object> params) {
        if (frameworkMethod().getMethod().getParameterTypes().length != params.size())
            return params;

        if (params.size() == 0)
            return params;

        Object param = params.get(0);
        if (param == null || !param.getClass().isArray())
            return params;

        return params;
    }

    private List<Object> fillResultWithAllParamProviderMethods(Class<?> sourceClass) {
        List<Object> result = getParamsFromSourceHierarchy(sourceClass);
        if (result.isEmpty())
            throw new RuntimeException(
                    "No methods starting with provide or they return no result in the parameters source class: "
                            + sourceClass.getName()
            );

        return result;
    }

    private List<Object> getParamsFromSourceHierarchy(Class<?> sourceClass) {
        List<Object> result = new ArrayList<>();
        while (sourceClass.getSuperclass() != null) {
            result.addAll(gatherParamsFromAllMethodsFrom(sourceClass));
            sourceClass = sourceClass.getSuperclass();
        }

        return result;
    }

    private List<Object> gatherParamsFromAllMethodsFrom(Class<?> sourceClass) {
        List<Object> result = new ArrayList<>();
        Method[] methods = sourceClass.getDeclaredMethods();
        for (Method providerMethod : methods) {
            if (providerMethod.getName().startsWith("provide")) {
                if (!Modifier.isStatic(providerMethod.getModifiers()))
                    throw new RuntimeException("Parameters source method " +
                            providerMethod.getName() +
                            " is not declared as static. Change it to a static method.");
                try {
                    result.addAll(getDataFromMethod(providerMethod));
                } catch (Exception e) {
                    throw new RuntimeException("Cannot invoke parameters source method: " + providerMethod.getName(), e);
                }
            }
        }
        return result;
    }

    private List<Object> getDataFromMethod(Method providerMethod) throws IllegalAccessException, InvocationTargetException {
        return encapsulateParamsIntoArrayIfSingleParamsetPassed(toList((Object[]) providerMethod.invoke(null)));
    }

    @Override
    public boolean isParameterised() {
        return super.isParameterised()
                || frameworkMethod().getMethod().isAnnotationPresent(ParameterRecords.class);
    }



    /*
    private List<Object> fillResultWithAllParamProviderMethods(List<Class<?>> sourceClasses) {
        List<Object> result = new ArrayList<>();
        for (Class<?> sourceClass : sourceClasses) {
            List<Object> innerResult = getParamsFromSourceHierarchy(sourceClass);
            if (result.isEmpty())
                throw new RuntimeException(
                        "No methods starting with provide or they return no result in the parameters source class: "
                                + sourceClass.getName()
                );
            result.addAll(innerResult);
        }

        return result;
    }

    private List<Object> getParamsFromSourceHierarchy(Class<?> sourceClass) {
        List<Object> result = new ArrayList<>();
        while (sourceClass.getSuperclass() != null) {
            result.addAll(gatherParamsFromAllMethodsFrom(sourceClass));
            sourceClass = sourceClass.getSuperclass();
        }

        return result;
    }

    private List<Object> gatherParamsFromAllMethodsFrom(Class<?> sourceClass) {
        List<Object> result = new ArrayList<>();
        Method[] methods = sourceClass.getDeclaredMethods();
        for (Method prividerMethod : methods) {
            if (prividerMethod.getName().startsWith("provide")) {
                if (!Modifier.isStatic(prividerMethod.getModifiers()))
                    throw new RuntimeException("Parameters source method " +
                            prividerMethod.getName() +
                            " is not declared as static. Change it to a static method.");
                try {
                    result.addAll(Arrays.asList(getDataFromMethod(prividerMethod)));
                } catch (Exception e) {
                    throw new RuntimeException("Cannot invoke parameters source method: " + prividerMethod.getName(), e);
                }
            }
        }
        return result;
    }

    private Object[] getDataFromMethod(Method prividerMethod) throws IllegalAccessException, InvocationTargetException {
        return encapsulateParamsIntoArrayIfSingleParamsetPassed((Object[]) prividerMethod.invoke(null));
    }

    private Object[] encapsulateParamsIntoArrayIfSingleParamsetPassed(Object[] params) {
        if (frameworkMethod().getMethod().getParameterTypes().length != params.length)
            return params;

        if (params.length == 0)
            return params;

        Object param = params[0];
        if (param == null || !param.getClass().isArray())
            return new Object[]{params};

        return params;
    }

    private List<Method> findParamsProvidingMethodsInTestclassHierarchy(String methodName, List<Class<?>> testClasses) {
        List<Method> provideMethods = new ArrayList<>();
        for (Class<?> testClass : testClasses) {
            Class<?> declaringClass = testClass;
            while (declaringClass.getSuperclass() != null) {
                try {
                    provideMethods.add(declaringClass.getDeclaredMethod(methodName));
                    break;
                } catch (NoSuchMethodException e) {
                    LOG.debug("No method named \"" + methodName + "\" could be found", e);
                }
                declaringClass = declaringClass.getSuperclass();
            }
        }
        if (provideMethods.isEmpty())
            throw new RuntimeException("Could not find method: " + methodName + " so no params were used.");
        return provideMethods;
    }

    @SuppressWarnings({"unchecked", "Convert2streamapi", "ConstantConditions"})
    private List<Object> invokeParamsProvidingMethod(List<Class<?>> testClasses, List<Method> provideMethods) {
        List<Object> resultingList = new ArrayList<>();
        for (Class<?> testClass : testClasses) {
            try {
                Object testObject = testClass.newInstance();
                for (Method provideMethod : provideMethods) {
                    try {
                        provideMethod.setAccessible(true);
                        Object result = provideMethod.invoke(testObject);
                        try {
                            Object[] params = (Object[]) result;
                            resultingList.addAll(Arrays.asList(encapsulateParamsIntoArrayIfSingleParamsetPassed(params)));
                        } catch (ClassCastException e) {
                            // Iterable
                            try {
                                ArrayList<Object[]> res = new ArrayList<>();
                                for (Object[] paramSet : (Iterable<Object[]>) result) {
                                    res.add(paramSet);
                                }
                                resultingList.addAll(res);
                            } catch (ClassCastException e1) {
                                // Iterable with consecutive paramsets, each of one param
                                ArrayList<Object> res = new ArrayList<>();
                                for (Object param : (Iterable<?>) result)
                                    res.add(new Object[]{param});
                                resultingList.addAll(res);
                            }
                        }
                    } catch (InvocationTargetException | IllegalAccessException e) {
                        throw new RuntimeException("Could not invoke method: " + provideMethod.getName() + " defined in class " + testClass
                                + " so no params were used.", e);
                    } catch (ClassCastException e) {
                        throw new RuntimeException("The return type of: " + provideMethod.getName() + " defined in class " + testClass
                                + " is not Object[][] nor Iterable<Object[]>. Fix it!", e);
                    } catch (Exception e) {
                        throw new RuntimeException("Could not invoke method: " + provideMethod.getName() + " defined in class " + testClass
                                + " so no params were used.", e);
                    }
                }
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException("Could not create instance of class " + testClass, e);
            }
        }
        return resultingList;
    }*/
}