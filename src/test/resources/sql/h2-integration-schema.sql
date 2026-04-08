create table user_account (
    id bigint primary key,
    name varchar(64),
    phone_cipher varchar(512),
    phone_hash varchar(128),
    phone_like varchar(255),
    id_card bigint
);

create table user_id_card_encrypt (
    id bigint auto_increment primary key,
    id_card_cipher varchar(512),
    id_card_hash varchar(128),
    id_card_like varchar(255)
);
