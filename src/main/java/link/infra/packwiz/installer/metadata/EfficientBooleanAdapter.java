package link.infra.packwiz.installer.metadata;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Gson TypeAdapter for booleans that reads 0/1 as false/true
 * and writes true as 1, false as 0 (more compact).
 */
public class EfficientBooleanAdapter extends TypeAdapter<Boolean> {
    @Override
    public void write(JsonWriter out, Boolean value) throws IOException {
        out.value(value ? 1 : 0);
    }

    @Override
    public Boolean read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NUMBER) {
            return in.nextInt() != 0;
        }
        return in.nextBoolean();
    }
}
