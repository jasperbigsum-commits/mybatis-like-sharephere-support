package io.github.jasper.mybatis.encrypt.migration.jdbc;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * Derives ciphertext, hash, like and stored-masked values from the original plaintext field.
 */
final class MigrationValueResolver {

    private final AlgorithmRegistry algorithmRegistry;

    MigrationValueResolver(AlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = algorithmRegistry;
    }

    DerivedFieldValues resolve(EntityMigrationColumnPlan columnPlan, Object plainValue) {
        if (plainValue == null) {
            return DerivedFieldValues.empty();
        }
        String plainText = String.valueOf(plainValue);
        if (StringUtils.isBlank(plainText)) {
            return DerivedFieldValues.empty();
        }
        String cipher = algorithmRegistry.cipher(columnPlan.getCipherAlgorithm()).encrypt(plainText);
        String hash = StringUtils.isBlank(columnPlan.getAssistedQueryColumn()) ? null
                : algorithmRegistry.assisted(columnPlan.getAssistedQueryAlgorithm()).transform(plainText);
        String like = StringUtils.isBlank(columnPlan.getLikeQueryColumn()) ? null
                : algorithmRegistry.like(columnPlan.getLikeQueryAlgorithm()).transform(plainText);
        String masked = StringUtils.isBlank(columnPlan.getMaskedColumn()) ? null
                : columnPlan.sharesLikeQueryAndMaskedColumn()
                ? like
                : algorithmRegistry.like(columnPlan.getEffectiveMaskedAlgorithm()).transform(plainText);
        return new DerivedFieldValues(cipher, hash, like, masked);
    }

    static final class DerivedFieldValues {

        private static final DerivedFieldValues EMPTY = new DerivedFieldValues(null, null, null, null);

        private final String cipherText;
        private final String hashValue;
        private final String likeValue;
        private final String maskedValue;

        private DerivedFieldValues(String cipherText, String hashValue, String likeValue, String maskedValue) {
            this.cipherText = cipherText;
            this.hashValue = hashValue;
            this.likeValue = likeValue;
            this.maskedValue = maskedValue;
        }

        static DerivedFieldValues empty() {
            return EMPTY;
        }

        boolean isEmpty() {
            return cipherText == null && hashValue == null && likeValue == null && maskedValue == null;
        }

        String getCipherText() {
            return cipherText;
        }

        String getHashValue() {
            return hashValue;
        }

        String getLikeValue() {
            return likeValue;
        }

        String getMaskedValue() {
            return maskedValue;
        }
    }
}
