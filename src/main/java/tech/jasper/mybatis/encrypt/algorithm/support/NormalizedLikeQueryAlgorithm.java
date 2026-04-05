package tech.jasper.mybatis.encrypt.algorithm.support;

import java.util.Locale;
import tech.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;

public class NormalizedLikeQueryAlgorithm implements LikeQueryAlgorithm {

    @Override
    public String transform(String plainText) {
        if (plainText == null) {
            return null;
        }
        return plainText.trim().toLowerCase(Locale.ROOT);
    }
}
