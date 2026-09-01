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

    /**
     * 返回已经把受保护 path 明文替换为 hash 的 JSON 字符串。
     *
     * @return 改写后的 JSON 字符串
     */
    public String rewrittenJson() {
        return rewrittenJson;
    }

    /**
     * 返回需要同步写入独立表的 path 结果。
     *
     * @return 不可变 path 写入结果列表
     */
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

        /**
         * 返回当前 path 对应的规则。
         *
         * @return JSON path 规则
         */
        public EncryptJsonPathRule pathRule() {
            return pathRule;
        }

        /**
         * 返回从 JSON path 读取到的原始明文值。
         *
         * @return 原始明文值
         */
        public String plainValue() {
            return plainValue;
        }

        /**
         * 返回由明文计算得到的 hash 引用值。
         *
         * @return hash 引用值
         */
        public String hashValue() {
            return hashValue;
        }

        /**
         * 返回由明文加密得到的密文值。
         *
         * @return 密文值
         */
        public String cipherValue() {
            return cipherValue;
        }
    }
}
