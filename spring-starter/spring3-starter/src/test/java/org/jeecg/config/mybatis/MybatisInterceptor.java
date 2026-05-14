package org.jeecg.config.mybatis;

import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.Properties;

@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class MybatisInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        Object parameter = unwrapParameter(invocation.getArgs()[1]);
        if (parameter == null) {
            return invocation.proceed();
        }
        if (SqlCommandType.INSERT == mappedStatement.getSqlCommandType()) {
            setIfEmpty(parameter, "createBy", "jeecg-user");
            setIfEmpty(parameter, "createTime", new Date());
            setIfEmpty(parameter, "sysOrgCode", "ORG-001");
            setIfEmpty(parameter, "tenantId", Integer.valueOf(99));
        } else if (SqlCommandType.UPDATE == mappedStatement.getSqlCommandType()) {
            set(parameter, "updateBy", "jeecg-updater");
            set(parameter, "updateTime", new Date());
        }
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }

    private static Object unwrapParameter(Object parameter) {
        if (parameter instanceof MapperMethod.ParamMap<?>) {
            MapperMethod.ParamMap<?> paramMap = (MapperMethod.ParamMap<?>) parameter;
            if (paramMap.containsKey("et")) {
                return paramMap.get("et");
            }
            return paramMap.get("param1");
        }
        return parameter;
    }

    private static void setIfEmpty(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        Object current = field.get(target);
        if (current == null || "".equals(current)) {
            field.set(target, value);
        }
        field.setAccessible(false);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        field.set(target, value);
        field.setAccessible(false);
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignore) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
