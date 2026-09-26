-- Protocol v1: 16 shards are permanent, independent of the number of application replicas.
CREATE FUNCTION outbox_shard(event_topic text, event_key text) RETURNS integer
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT get_byte(decode(md5(event_topic || chr(31) || event_key), 'hex'), 0) % 16 $$;

CREATE TABLE outbox_shard_owners (
    shard_id integer PRIMARY KEY CHECK (shard_id >= 0 AND shard_id < 16),
    owner_id uuid,
    claim_token uuid,
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    last_claimed_at timestamptz NOT NULL DEFAULT '-infinity',
    retry_after timestamptz NOT NULL DEFAULT '-infinity'
);
INSERT INTO outbox_shard_owners(shard_id) SELECT generate_series(0, 15);

CREATE INDEX idx_outbox_events_shard_pending
    ON outbox_events (outbox_shard(topic, event_key), created_at, id)
    WHERE published_at IS NULL;

-- Application clocks can disagree; a random UUID tie-breaker cannot fix business order.
-- Lock this per-key clock until the INSERT's business transaction commits. A later INSERT
-- for that key cannot become visible before its predecessor. Existing writers remain valid.
CREATE TABLE outbox_key_clocks (
    topic varchar(100) NOT NULL,
    event_key varchar(100) NOT NULL,
    last_created_at timestamp NOT NULL,
    PRIMARY KEY(topic, event_key)
);
INSERT INTO outbox_key_clocks(topic, event_key, last_created_at)
    SELECT topic, event_key, max(created_at) FROM outbox_events GROUP BY topic, event_key;

CREATE FUNCTION outbox_assign_created_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO outbox_key_clocks(topic, event_key, last_created_at)
        VALUES (NEW.topic, NEW.event_key, clock_timestamp() AT TIME ZONE 'UTC')
    ON CONFLICT (topic, event_key) DO UPDATE SET last_created_at =
        greatest(outbox_key_clocks.last_created_at + interval '1 microsecond',
                 clock_timestamp() AT TIME ZONE 'UTC')
    RETURNING last_created_at INTO NEW.created_at;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbox_events_order BEFORE INSERT ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION outbox_assign_created_at();

