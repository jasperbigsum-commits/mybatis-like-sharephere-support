create table user_account (
    id bigint primary key,
    name varchar(64),
    phone_cipher varchar(512),
    phone_hash varchar(128),
    phone_like varchar(255),
    phone_masked varchar(255),
    id_card varchar(128)
);

create table order_account (
    id bigint primary key,
    user_id bigint,
    related_user_id bigint,
    created_seq bigint,
    remark varchar(255),
    owner_name varchar(64),
    deleted tinyint
);

create table order_participant (
    id bigint primary key,
    order_id bigint,
    user_id bigint,
    seq_no int
);

create table user_id_card_encrypt (
    id bigint primary key,
    id_card_cipher varchar(512),
    id_card_hash varchar(128),
    id_card_like varchar(255),
    id_card_masked varchar(255)
);
