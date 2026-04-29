package uni.post.record;

import uni.post.entity.Post;

public record PostResult(Post post, boolean likedByMe) {
}
