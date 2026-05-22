package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSON 写路径改写结果。
 */
public final class EncryptJsonWriteResult {

    private final String rewrittenJson;
    private final List<PathWrite> pathWrites;

    /**
     * 创建 JSON 写路径改写结果。
     *
     * @param rewrittenJson 改写后的 JSON
     * @param pathWrites 需要写入独立表的 path 结果
     */
    public EncryptJsonWriteResult(String rewrittenJson, List<PathWrite> pathWrites) {
        this.rewrittenJson = rewrittenJson;
        this.pathWrites = pathWrites == null
                ? Collections.<PathWrite>emptyList()
                : Collections.unmodifiableList(new ArrayList<PathWrite>(pathWrites));
    }

    public String rewrittenJson() {
        return rewrittenJson;
    }

    public List<PathWrite> pathWrites() {
        return pathWrites;
    }

    /**
     * 单个 JSON path 的外表写入信息。
     */
    public static final class PathWrite {

        private final EncryptJsonPathRule pathRule;
        private final String plainValue;
        private final String hashValue;
        private final String cipherValue;

        /**
         * 创建单个 path 写入描述。
         *
         * @param pathRule 当前 path 规则
         * @param plainValue 原始明文
         * @param hashValue 计算后的 hash
         * @param cipherValue 计算后的密文
         */
        public PathWrite(EncryptJsonPathRule pathRule,
                         String plainValue,
                         String hashValue,
                         String cipherValue) {
            this.pathRule = pathRule;
            this.plainValue = plainValue;
            this.hashValue = hashValue;
            this.cipherValue = cipherValue;
        }

        public EncryptJsonPathRule pathRule() {
            return pathRule;
        }

        public String plainValue() {
            return plainValue;
        }

        public String hashValue() {
            return hashValue;
        }

        public String cipherValue() {
            return cipherValue;
        }
    }
}
