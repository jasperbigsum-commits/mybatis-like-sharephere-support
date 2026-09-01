package io.github.jasper.mybatis.encrypt.autoconfigure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.UpdateProvider;

public interface AutoConfiguredUserMapper {

    @Insert("insert into user_account (id, name, phone, id_card, create_by, create_time, sys_org_code, tenant_id) " +
            "values (#{id}, #{name}, #{phone}, #{idCard}, #{createBy}, #{createTime}, #{sysOrgCode}, #{tenantId})")
    int insertUser(AutoConfiguredUserRecord user);

    @UpdateProvider(type = AutoConfiguredUserSqlProvider.class, method = "updateUser")
    int updateUser(AutoConfiguredUserRecord user);

    @Select("select id, name, phone, id_card " +
            "from user_account " +
            "where phone = #{phone}")
    AutoConfiguredUserRecord selectByPhone(@Param("phone") String phone);

    @Select("select id, name, phone, id_card " +
            "from user_account " +
            "where id_card = #{idCard}")
    AutoConfiguredUserRecord selectByIdCard(@Param("idCard") String idCard);

    class AutoConfiguredUserSqlProvider {

        public String updateUser(AutoConfiguredUserRecord user) {
            StringBuilder sql = new StringBuilder();
            sql.append("update user_account set name = #{name}");
            if (user.getPhone() != null) {
                sql.append(", phone = #{phone}");
            }
            sql.append(", id_card = #{idCard}, update_by = #{updateBy}, update_time = #{updateTime}");
            sql.append(" where id = #{id}");
            return sql.toString();
        }
    }
}
