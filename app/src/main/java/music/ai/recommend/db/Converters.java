package music.ai.recommend.db;

import androidx.room.TypeConverter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;

public class Converters {
    private static final Gson gson = new Gson();

    @TypeConverter
    public static String fromFloatList(List<Float> value) {
        return gson.toJson(value);
    }

    @TypeConverter
    public static List<Float> toFloatList(String value) {
        Type listType = new TypeToken<List<Float>>() {}.getType();
        return gson.fromJson(value, listType);
    }
}
