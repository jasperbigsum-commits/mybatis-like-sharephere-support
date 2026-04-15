package io.github.jasper.mybatis.encrypt.algorithm.support;

import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;

/**
 * 中文名称脱敏 LIKE 算法。
 *
 * <p>个人姓名按常见业务规则处理：
 * 双字姓名保留首字，三字及以上保留首尾字。
 * 组织名称按启发式规则处理：
 * 识别出公司/机构类名称后，优先保留前置地名、其后的前 2 个字符以及末尾 2 个字符。</p>
 *
 * <p>机构名称识别和地名前缀识别都属于启发式判断，适合常见中文业务名称，
 * 不保证覆盖所有企业简称或非常规命名。</p>
 */
public final class NameMaskLikeQueryAlgorithm implements LikeQueryAlgorithm {

    private static final char MASK_CHAR = '*';

    private static final String[] ORGANIZATION_KEYWORDS = {
            "有限责任公司", "股份有限公司", "有限公司", "集团", "公司", "银行", "保险", "证券",
            "医院", "学校", "大学", "学院", "中心", "研究院", "研究所", "事务所", "合作社",
            "委员会", "工作室", "门诊", "诊所", "俱乐部", "协会", "超市", "酒店", "宾馆",
            "门店", "工厂", "厂", "店", "企业"
    };

    private static final String[] LOCATION_SUFFIXES = {
            "特别行政区", "自治区", "自治州", "自治县", "地区", "盟", "州", "省", "市", "区", "县", "旗", "镇", "乡", "村"
    };

    @Override
    public String transform(String plainText) {
        if (null == plainText || plainText.isEmpty()) {
            return plainText;
        }
        return looksLikeOrganization(plainText) ? maskOrganization(plainText) : maskPersonalName(plainText);
    }

    private String maskPersonalName(String plainText) {
        if (plainText.length() <= 1) {
            return plainText;
        }
        if (plainText.length() == 2) {
            return plainText.charAt(0) + String.valueOf(MASK_CHAR);
        }
        return plainText.charAt(0) + repeat(MASK_CHAR, plainText.length() - 2) + plainText.charAt(plainText.length() - 1);
    }

    private String maskOrganization(String plainText) {
        int prefixLength = detectLocationPrefixLength(plainText);
        int visibleStart = Math.min(prefixLength + 2, plainText.length());
        int visibleEnd = Math.max(visibleStart, plainText.length() - 2);
        if (visibleStart >= visibleEnd) {
            return plainText;
        }
        StringBuilder builder = new StringBuilder(plainText.length());
        builder.append(plainText, 0, visibleStart);
        builder.append(repeat(MASK_CHAR, visibleEnd - visibleStart));
        builder.append(plainText.substring(visibleEnd));
        return builder.toString();
    }

    private boolean looksLikeOrganization(String plainText) {
        if (plainText.length() < 5) {
            return false;
        }
        for (String keyword : ORGANIZATION_KEYWORDS) {
            if (plainText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int detectLocationPrefixLength(String plainText) {
        int detected = 0;
        int upperBound = Math.min(plainText.length() - 2, 8);
        for (int end = 1; end <= upperBound; end++) {
            String prefix = plainText.substring(0, end);
            for (String suffix : LOCATION_SUFFIXES) {
                if (prefix.endsWith(suffix)) {
                    detected = end;
                    break;
                }
            }
        }
        return detected;
    }

    private String repeat(char ch, int count) {
        if (count <= 0) {
            return "";
        }
        char[] chars = new char[count];
        for (int index = 0; index < count; index++) {
            chars[index] = ch;
        }
        return new String(chars);
    }
}
