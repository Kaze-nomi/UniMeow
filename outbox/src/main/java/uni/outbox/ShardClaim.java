package uni.outbox;

import java.util.UUID;

public record ShardClaim(int shard, UUID owner, UUID token) {
}
