package music.ai.recommend.db;

import androidx.room.*;
import java.util.List;

@Dao
public interface EmbeddingDao {
    @Query("SELECT * FROM embeddings")
    List<EmbeddingEntity> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(EmbeddingEntity embedding);

    @Query("SELECT COUNT(*) FROM embeddings")
    int getCount();
}
