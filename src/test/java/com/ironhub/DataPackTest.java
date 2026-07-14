package com.ironhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Schema-validates every data pack under src/main/resources/data/ against
 * data/schemas/&lt;name&gt;.schema.json. Every pack must have a schema.
 */
public class DataPackTest
{
	private static final Path DATA_DIR = Paths.get("src/main/resources/data");

	@Test
	public void allDataPacksValidateAgainstTheirSchemas() throws IOException
	{
		ObjectMapper mapper = new ObjectMapper();
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
		int validated = 0;

		try (DirectoryStream<Path> packs = Files.newDirectoryStream(DATA_DIR, "*.json"))
		{
			for (Path pack : packs)
			{
				Path schemaPath = DATA_DIR.resolve("schemas")
					.resolve(pack.getFileName().toString().replace(".json", ".schema.json"));
				assertTrue("missing schema for data pack: " + pack, Files.exists(schemaPath));

				JsonSchema schema = factory.getSchema(mapper.readTree(schemaPath.toFile()));
				JsonNode data = mapper.readTree(pack.toFile());
				Set<ValidationMessage> errors = schema.validate(data);
				assertTrue(pack + ": " + errors, errors.isEmpty());
				validated++;
			}
		}

		assertTrue("no data packs found under " + DATA_DIR, validated > 0);
	}
}
