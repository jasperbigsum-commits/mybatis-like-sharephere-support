package tech.jasper.mybatis.encrypt.autoconfigure;

import tech.jasper.mybatis.encrypt.annotation.EncryptField;
import tech.jasper.mybatis.encrypt.annotation.EncryptTable;
import tech.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

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
            storageIdColumn = "user_id",
            assistedQueryColumn = "id_card_hash",
            likeQueryColumn = "id_card_like"
    )
    private String idCard;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }
}
