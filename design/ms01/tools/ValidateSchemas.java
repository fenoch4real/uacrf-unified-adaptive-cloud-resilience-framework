import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
public class ValidateSchemas {
 public static void main(String[] args) throws Exception {
  ObjectMapper mapper=new ObjectMapper();
  JsonSchemaFactory factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  var options=SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
  var cases=mapper.readTree(Files.readString(Path.of(args[1])));
  int passed=0;
  for(var item:cases){
   var schema=factory.getSchema(mapper.readTree(Files.readString(Path.of(args[0],item.path("schema").asText()))),options);
   boolean valid=schema.validate(item.path("value")).isEmpty();
   if(valid!=item.path("valid").asBoolean()) throw new IllegalStateException("Schema fixture failed: "+item.path("name").asText());
   passed++;
  }
  System.out.println("Validated "+passed+" positive and negative structural schema fixtures");
 }
}
