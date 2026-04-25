package io.github.jasper.mybatis.encrypt.algorithm.support;

/**
 * 仅保留闭区间 [X, Y]，区间外按替换字符覆盖。
 *
 * <p>语义参考 Apache ShardingSphere `KEEP_FROM_X_TO_Y`，索引按 0-based 闭区间处理。</p>
 */
public final class KeepFromXToYLikeQueryAlgorithm extends AbstractMaskLikeQueryAlgorithm {

    private final int fromX;
    private final int toY;
    private final char replaceChar;

    /**
     * 创建仅保留闭区间 [X, Y] 的 LIKE 预处理算法。
     *
     * @param fromX 保留区间起点
     * @param toY 保留区间终点
     */
    public KeepFromXToYLikeQueryAlgorithm(int fromX, int toY) {
        this(fromX, toY, DEFAULT_REPLACE_CHAR);
    }

    /**
     * 创建仅保留闭区间 [X, Y] 的 LIKE 预处理算法。
     *
     * @param fromX 保留区间起点
     * @param toY 保留区间终点
     * @param replaceChar 区间外覆盖字符
     */
    public KeepFromXToYLikeQueryAlgorithm(int fromX, int toY, char replaceChar) {
        requireNonNegative("fromX", fromX);
        requireNonNegative("toY", toY);
        requireRange(fromX, toY);
        this.fromX = fromX;
        this.toY = toY;
        this.replaceChar = replaceChar;
    }

    @Override
    public String transform(String plainText) {
        return transformLiteralSegments(plainText, this::maskSegment);
    }

    private String maskSegment(String plainText) {
        char[] chars = plainText.toCharArray();
        for (int index = 0; index < Math.min(fromX, plainText.length()); index++) {
            chars[index] = replaceChar;
        }
        for (int index = toY + 1; index < chars.length; index++) {
            chars[index] = replaceChar;
        }
        return new String(chars);
    }
}
