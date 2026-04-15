package io.github.jasper.mybatis.encrypt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot 2 环境下对外暴露的配置绑定入口。
 *
 * <p>直接复用公共模块的 {@link DatabaseEncryptionProperties}，避免 starter 再维护一套
 * 完全重复的字段定义，只保留配置绑定职责。</p>
 */
@ConfigurationProperties(prefix = "mybatis.encrypt")
public class UserDatabaseEncryptionProperties extends DatabaseEncryptionProperties {
}
