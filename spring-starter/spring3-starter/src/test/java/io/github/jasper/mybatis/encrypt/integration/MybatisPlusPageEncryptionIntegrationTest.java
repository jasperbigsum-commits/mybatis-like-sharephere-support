package io.github.jasper.mybatis.encrypt.integration;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptResultHint;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveExtraInfoSupport;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.Alias;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the complete MyBatis-Plus IPage path rather than invoking the
 * ResultSetHandler with a hand-built wrapper.
 */
@SpringBootTest(
        classes = MybatisPlusPageEncryptionIntegrationTest.TestApplication.class,
        properties = {
                "mybatis.encrypt.enabled=true",
                "mybatis.encrypt.default-cipher-key=page-integration-key",
                "mybatis.encrypt.scan-entity-annotations=true",
                "mybatis.encrypt.scan-packages=io.github.jasper.mybatis.encrypt.integration",
                "mybatis-plus.mapper-locations=classpath:/no-page-test-mappers/*.xml",
                "mybatis-plus.configuration.map-underscore-to-camel-case=true"
        }
)
@Tag("integration")
@Tag("mybatis-plus")
class MybatisPlusPageEncryptionIntegrationTest {

    private static final String PHONE = "13800138000";

    @jakarta.annotation.Resource
    private DataSource dataSource;

    @jakarta.annotation.Resource
    private PageMapper mapper;

    @jakarta.annotation.Resource
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists customer");
            statement.execute("create table customer (id bigint primary key, real_name varchar(128), real_name_cipher varchar(512), mobile varchar(128), mobile_cipher varchar(512), id_card_no varchar(128), id_card_no_cipher varchar(512))");
        }
        Sm4CipherAlgorithm cipherAlgorithm = new Sm4CipherAlgorithm("page-integration-key");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into customer (id, real_name, real_name_cipher, mobile, mobile_cipher, id_card_no, id_card_no_cipher) values (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, 1L);
            statement.setString(2, "legacy-name-column");
            statement.setString(3, cipherAlgorithm.encrypt("张三"));
            statement.setString(4, "legacy-mobile-column");
            statement.setString(5, cipherAlgorithm.encrypt(PHONE));
            statement.setString(6, "legacy-id-card-column");
            statement.setString(7, cipherAlgorithm.encrypt("320101199001011234"));
            statement.executeUpdate();
        }
    }

    @Test
    void shouldDecryptEncryptedProjectionThroughRealMybatisPlusPage() {
        assertTrue(sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .anyMatch(DatabaseEncryptionInterceptor.class::isInstance));

        IPage<PageCustomerView> page = mapper.queryCanApplyList(new PageQuery(), new Page<>(1, 10));

        assertEquals(1, page.getTotal());
        assertEquals(1, page.getRecords().size());
        PageCustomerView result = page.getRecords().get(0);
        assertEquals(1L, result.getId());
        assertEquals("张三", result.getRealName());
        assertEquals(PHONE, result.getMobile());
        assertEquals("320101199001011234", result.getIdCardNo());
        assertNotEquals("legacy-name-column", result.getRealName());
        assertNotEquals("legacy-mobile-column", result.getMobile());
        assertNotEquals("legacy-id-card-column", result.getIdCardNo());
    }

    @Mapper
    interface PageMapper {
        @EncryptResultHint(entities = Customer.class)
        @Select("select c.id, c.real_name as realName, c.mobile as mobile, c.id_card_no as idCardNo from customer c")
        IPage<PageCustomerView> queryCanApplyList(@Param("dto") PageQuery dto,
                                                  IPage<PageCustomerView> page);
    }

    static class PageQuery {
    }

    @Alias("Customer")
    static class Customer extends Model<Customer> {
        private Long id;

        @EncryptField(storageColumn = "real_name_cipher")
        private String realName;

        @EncryptField(storageColumn = "mobile_cipher")
        private String mobile;

        @EncryptField(storageColumn = "id_card_no_cipher")
        private String idCardNo;
    }

    static class PageCustomerView extends SensitiveExtraInfoSupport {
        private Long id;
        private String realName;
        private String mobile;
        private String idCardNo;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getMobile() {
            return mobile;
        }

        public void setMobile(String mobile) {
            this.mobile = mobile;
        }

        public String getIdCardNo() {
            return idCardNo;
        }

        public void setIdCardNo(String idCardNo) {
            this.idCardNo = idCardNo;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = PageMapper.class)
    static class TestApplication {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:mybatis_plus_page_encrypt;MODE=MYSQL;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor() {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.H2));
            return interceptor;
        }
    }
}
