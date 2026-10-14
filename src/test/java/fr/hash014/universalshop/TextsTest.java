package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TextsTest {
  @Test void LanguageSelectionAcceptsKnownRegionalForms() {
    assertEquals("en", Texts.language("EN-us"));
    assertEquals("fr", Texts.language("fr_FR"));
    assertThrows(IllegalArgumentException.class, () -> Texts.language("de"));
  }

  @Test void SubstitutionDoesNotInterpretRegexOrCommands() {
    assertEquals("Balance: $1.00 / [x]", Texts.replace("Balance: {money} / {name}", "money", "$1.00", "name", "[x]"));
    assertThrows(IllegalArgumentException.class, () -> Texts.replace("{x}", "x"));
  }

  @Test void EditorTranslationPreservesTemplateVariables() {
    YamlConfiguration en = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/languages/en.yml"), StandardCharsets.UTF_8));
    YamlConfiguration fr = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/languages/fr.yml"), StandardCharsets.UTF_8));
    for (String key : en.getKeys(true)) {
      if (!key.startsWith("editor.") || en.isConfigurationSection(key)) continue;
      assertTrue(fr.isString(key), key);
      String original = en.getString(key), translated = fr.getString(key);
      java.util.regex.Matcher variables = java.util.regex.Pattern.compile("\\{[^}]+\\}").matcher(original);
      while (variables.find()) assertTrue(translated.contains(variables.group()), key + " lost " + variables.group());
    }
    assertEquals("&eNom affiché", fr.getString("editor.field.name"));
    assertTrue(fr.getMapList("phrases").size() > 50);
  }
}
