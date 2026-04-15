package io.github.jasper.mybatis.encrypt.autoconfigure;

import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import lombok.Data;

@Data
@EncryptTable("user_account")
public class AutoConfiguredUserRecord {

    private Long id;
    private String name;

    @EncryptField(
            column = "phone",
            storageColumn = "phone_cipher",
            assistedQueryColumn = "phone_hash",
            likeQueryColumn = "phone_like"
    )
    private String phone;

    @EncryptField(
            column = "id_card",
            storageMode = FieldStorageMode.SEPARATE_TABLE,
            storageTable = "user_id_card_encrypt",
            storageColumn = "id_card_cipher",
            storageIdColumn = "id",
            assistedQueryColumn = "id_card_hash",
            likeQueryColumn = "id_card_like"
    )
    private String idCard;
}
