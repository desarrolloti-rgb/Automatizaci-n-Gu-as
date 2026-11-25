package com.calimport.guias;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Límites entre paquetes que el build comprueba solo (ver "Cómo trabajar en este repo" en
 * CLAUDE.md). Las excepciones están nombradas una por una: un controller nuevo que hable con
 * SAP falla acá, en vez de sumarse callado a los que ya lo hacen.
 */
@AnalyzeClasses(packages = "com.calimport.guias", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    /**
     * Controllers que hablan con SAP directo, a sabiendas:
     * - {@code DeliveryNoteController}: es una ventana de diagnóstico al JSON crudo, no parte de la app.
     * - {@code AuthController} y {@code SesionComunController}: el login y el canje de la sesión
     *   común le preguntan a {@code EmployeesInfo}. Es deuda: debería ir en un servicio.
     */
    private static final String[] CONTROLLERS_CON_SAP = {
            "com.calimport.guias.controller.DeliveryNoteController",
            "com.calimport.guias.controller.AuthController",
            "com.calimport.guias.controller.SesionComunController",
    };

    @ArchTest
    static final ArchRule controllersNoTocanLaBase = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .because("un controller delega en service; las consultas parciales viven ahí");

    @ArchTest
    static final ArchRule controllersNoLlamanAServiciosExternos = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..google..", "..osm..", "..correo..")
            .because("los clientes externos se usan desde service");

    @ArchTest
    static final ArchRule controllersNoLlamanASap = noClasses()
            .that().resideInAPackage("..controller..")
            .and().doNotHaveFullyQualifiedName(CONTROLLERS_CON_SAP[0])
            .and().doNotHaveFullyQualifiedName(CONTROLLERS_CON_SAP[1])
            .and().doNotHaveFullyQualifiedName(CONTROLLERS_CON_SAP[2])
            .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAPackage("..sap..")
                            // Atrapar el 401 de SAP para responder 502 es manejo de error, no una consulta.
                            .and(DescribedPredicate.not(JavaClass.Predicates.simpleName("SapUnauthorizedException"))))
            .because("las consultas a SAP y el parseo de su JSON van en service o sap");

    @ArchTest
    static final ArchRule nadieDependeDeLosControllers = noClasses()
            .that().resideOutsideOfPackage("..controller..")
            .should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage("..controller..")
                    // RutaService recibe y devuelve los DTO de rutas; los controllers en sí, nadie.
                    .and(JavaClass.Predicates.resideOutsideOfPackage("..controller.dto..")))
            .because("la web es la capa de arriba");

    @ArchTest
    static final ArchRule repositoriosSoloConocenElModelo = noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..service..", "..sap..", "..google..", "..osm..", "..correo..", "..security..")
            .because("un repositorio solo sabe de entidades");

    @ArchTest
    static final ArchRule elModeloNoDependeDeNadie = noClasses()
            .that().resideInAPackage("..model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..service..", "..repository..", "..sap..", "..google..",
                    "..osm..", "..correo..", "..security..", "..config..")
            .because("las entidades son la base de todo lo demás");

    @ArchTest
    static final ArchRule integracionesNoSubenALaWebNiALaBase = noClasses()
            .that().resideInAnyPackage("..sap..", "..google..", "..osm..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..repository..")
            .because("un cliente externo solo traduce entre su API y el dominio");
}
