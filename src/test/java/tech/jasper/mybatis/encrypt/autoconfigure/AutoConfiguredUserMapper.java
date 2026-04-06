package tech.jasper.mybatis.encrypt.autoconfigure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AutoConfiguredUserMapper {

    @Insert("""
            insert into user_account (id, name, phone, id_card)
            values (#{id}, #{name}, #{phone}, #{idCard})
            """)
    int insertUser(AutoConfiguredUserRecord user);

    @Select("""
            select id, name, phone, id_card
            from user_account
            where phone = #{phone}
            """)
    AutoConfiguredUserRecord selectByPhone(@Param("phone") String phone);

    @Select("""
            select id, name, phone, id_card
            from user_account
            where id_card = #{idCard}
            """)
    AutoConfiguredUserRecord selectByIdCard(@Param("idCard") String idCard);
}
