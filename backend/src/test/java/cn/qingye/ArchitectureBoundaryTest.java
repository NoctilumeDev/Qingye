package cn.qingye;

import cn.qingye.business.CapacityScan;
import cn.qingye.business.Problem;
import cn.qingye.db.Sql;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.QueryPlan;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded existing invariants, not a claim that the whole monolith is acyclic. */
class ArchitectureBoundaryTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("cn.qingye");

    private static ArchRule modelRule(String packageName) {
        return classes().that().resideInAPackage(packageName)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                packageName, "java.lang..", "java.time..", "java.util..")
            .as("Model contracts must not depend on runtime or framework components");
    }

    private static ArchRule capacityRule(Class<?> type) {
        return classes().that().haveFullyQualifiedName(type.getName())
            .should().onlyDependOnClassesThat(new DescribedPredicate<JavaClass>("model, Problem and standard value types") {
                @Override public boolean test(JavaClass dependency) {
                    return dependency.isEquivalentTo(type) || dependency.isEquivalentTo(Problem.class)
                        || dependency.getPackageName().equals("cn.qingye.model")
                        || standardPackage(dependency.getPackageName(), "java.lang")
                        || standardPackage(dependency.getPackageName(), "java.time")
                        || standardPackage(dependency.getPackageName(), "java.util");
                }
            });
    }

    private static boolean standardPackage(String actual, String allowed) {
        return actual.equals(allowed) || actual.startsWith(allowed + ".");
    }

    private static ArchRule plannerRule(Class<?> type) {
        return classes().that().haveFullyQualifiedName(type.getName())
            .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                "cn.qingye.db..", "cn.qingye.business..", "cn.qingye.api..",
                "java.sql..", "javax.sql..", "org.springframework.jdbc..")
            .as("The model planner must not acquire database, business execution or API dependencies");
    }

    @Test void importsRealProductionClassesAndNoTestFixtures() {
        assertTrue(PRODUCTION.contain(QueryPlan.class));
        assertTrue(PRODUCTION.contain(CapacityScan.class));
        assertTrue(PRODUCTION.contain(LlmPlanner.class));
        assertFalse(PRODUCTION.contain(InvalidPlanner.class));
    }

    @Test void modelContractsStayIndependent() {
        modelRule("cn.qingye.model..").check(PRODUCTION);
    }

    @Test void plannerHasNoDirectBusinessApiOrDatabaseDependencies() {
        plannerRule(LlmPlanner.class).check(PRODUCTION);
    }

    @Test void capacityCalculationStaysIndependent() {
        capacityRule(CapacityScan.class).check(PRODUCTION);
    }

    @Test void plannerRuleRejectsARealDatabaseFieldInBytecode() {
        var result = plannerRule(InvalidPlanner.class).evaluate(
            new ClassFileImporter().importClasses(InvalidPlanner.class));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains(Sql.class.getName()));
    }

    @Test void capacityRuleRejectsARealDatabaseFieldInBytecode() {
        var result = capacityRule(InvalidCapacity.class).evaluate(
            new ClassFileImporter().importClasses(InvalidCapacity.class));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains(Sql.class.getName()));
    }

    @Test void modelRuleRejectsAFrameworkDependencyInBytecode() {
        var result = modelRule("qualityfixtures.model..").evaluate(
            new ClassFileImporter().importPackages("qualityfixtures.model"));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains("org.springframework.jdbc.core.JdbcTemplate"));
    }

    static class InvalidPlanner { Sql database; }
    static class InvalidCapacity { Sql database; }
}
