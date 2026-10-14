package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class AdminPermissionsTest {
  private Predicate<String> nodes(String... nodes) {
    Set<String> values = new HashSet<String>(Arrays.asList(nodes));
    return values::contains;
  }

  @Test void stockDelegationDoesNotGrantBalanceOrConfiguration() {
    Predicate<String> stock = nodes("universalshop.admin.stock");
    assertTrue(AdminPermissions.allowed(stock, "stock"));
    assertTrue(AdminPermissions.allowed(stock, "admin.manage.restock"));
    assertTrue(AdminPermissions.allowed(stock, "manage.reset-demand"));
    assertTrue(AdminPermissions.allowed(stock, "panel"));
    assertFalse(AdminPermissions.allowed(stock, "balance"));
    assertFalse(AdminPermissions.allowed(stock, "manage.disable"));
    assertFalse(AdminPermissions.allowed(stock, "reload"));
    assertFalse(AdminPermissions.allowed(stock, "recovery"));
  }

  @Test void CatalogDelegationDoesNotGrantConsoleCommandEditing() {
    Predicate<String> catalog = nodes("universalshop.admin.catalog");
    assertTrue(AdminPermissions.allowed(catalog, "editor"));
    assertTrue(AdminPermissions.allowed(catalog, "admin.add"));
    assertTrue(AdminPermissions.allowed(catalog, "diagnose"));
    assertTrue(AdminPermissions.allowed(catalog, "sign"));
    assertFalse(AdminPermissions.allowed(catalog, "commands"));
    assertFalse(AdminPermissions.allowed(catalog, "command"));
  }

  @Test void HistoryAndBackupAreIndependent() {
    Predicate<String> history = nodes("universalshop.admin.history");
    assertTrue(AdminPermissions.allowed(history, "manage.stats"));
    assertTrue(AdminPermissions.allowed(history, "manage.report"));
    assertFalse(AdminPermissions.allowed(history, "export"));
    assertFalse(AdminPermissions.allowed(history, "backup"));
    assertTrue(AdminPermissions.allowed(nodes("universalshop.admin.backup"), "export"));
  }

  @Test void LegacyNodeRetainsCompleteAdministration() {
    Predicate<String> legacy = nodes("universalshop.admin");
    for (String scope : AdminPermissions.SCOPES) assertTrue(AdminPermissions.allowed(legacy, scope));
    assertTrue(AdminPermissions.allowed(legacy, "panel"));
    assertFalse(AdminPermissions.allowed(nodes(), "panel"));
  }
}
