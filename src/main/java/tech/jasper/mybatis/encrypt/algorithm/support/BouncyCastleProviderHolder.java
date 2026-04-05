package tech.jasper.mybatis.encrypt.algorithm.support;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

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
