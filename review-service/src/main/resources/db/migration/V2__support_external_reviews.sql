alter table reviews alter column user_id drop not null;
alter table reviews alter column order_item_id drop not null;
alter table reviews add column source varchar(20) not null default 'FITZZA';
alter table reviews add column source_review_id bigint;

alter table reviews
    add constraint reviews_source_check check (source in ('FITZZA', 'MUSINSA'));

alter table reviews
    add constraint reviews_identity_check check (
        (source = 'FITZZA'
            and user_id is not null
            and order_item_id is not null
            and source_review_id is null)
        or
        (source = 'MUSINSA'
            and user_id is null
            and order_item_id is null
            and source_review_id is not null)
    );

alter table reviews
    add constraint reviews_rating_check check (rating between 1 and 5);

alter table reviews
    add constraint uk_reviews_source_identity unique (source, source_review_id);

create index idx_reviews_source on reviews (source);
