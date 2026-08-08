package music.ai.recommend.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.util.List;

@Entity(tableName = "embeddings")
public class EmbeddingEntity {
    @PrimaryKey
    public long songId;
    public List<Float> vector;

    public EmbeddingEntity(long songId, List<Float> vector) {
        this.songId = songId;
        this.vector = vector;
    }
}
