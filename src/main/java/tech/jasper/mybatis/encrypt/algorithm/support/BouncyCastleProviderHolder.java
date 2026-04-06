package tech.jasper.mybatis.encrypt.algorithm.support;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * BouncyCastle 安全提供者持有者。
 *
 * <p>通过类加载时的 static 块保证 BouncyCastle Provider 只注册一次，
 * 外部通过 {@link #ensureRegistered()} 触发类加载即可。</p>
 */
final class BouncyCastleProviderHolder {

    static final String PROVIDER_NAME = BouncyCastleProvider.PROVIDER_NAME;

    static {
        if (Security.getProvider(PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private BouncyCastleProviderHolder() {
    }

    static void ensureRegistered() {
    }
}
