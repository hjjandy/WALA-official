import com.ibm.wala.gradle.cast.addCastLibrary
import com.ibm.wala.gradle.cast.addRpath
import org.gradle.api.attributes.LibraryElements.CLASSES
import org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE
import org.gradle.api.attributes.LibraryElements.RESOURCES
import org.gradle.api.attributes.Usage.NATIVE_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE

plugins {
  `cpp-application`
  id("com.ibm.wala.gradle.cast.native")
  id("com.ibm.wala.gradle.subproject")
}

val coreResources: Configuration by
    configurations.creating {
      isCanBeConsumed = false
      isTransitive = false
      attributes {
        attribute(LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class, RESOURCES))
      }
    }

val smokeMainExtraPathElements: Configuration by
    configurations.creating {
      isCanBeConsumed = false
      isTransitive = false
      attributes {
        attribute(LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class, CLASSES))
      }
    }

fun createXlatorConfig(isOptimized: Boolean): Configuration =
    configurations.create(
        "xlatorTest${if (isOptimized) "Release" else "Debug"}SharedLibraryConfig") {
          isCanBeConsumed = false
          isTransitive = false
          attributes {
            attribute(OPTIMIZED_ATTRIBUTE, isOptimized)
            attribute(USAGE_ATTRIBUTE, objects.named(Usage::class, NATIVE_RUNTIME))
          }
        }

val xlatorTestDebugSharedLibraryConfig = createXlatorConfig(false)

val xlatorTestReleaseSharedLibraryConfig = createXlatorConfig(true)

application {
  // Temporary change to build on M1 Mac machines, until
  // https://github.com/gradle/gradle/issues/18876
  // is fixed
  if (rootProject.extra["osName"] == "Mac OS X" && rootProject.extra["archName"] == "aarch64") {
    targetMachines.add(machines.macOS.x86_64)
  }

  dependencies {
    coreResources(projects.core)
    smokeMainExtraPathElements(projects.cast)
    smokeMainExtraPathElements(projects.core)
    smokeMainExtraPathElements(projects.util)
    implementation(projects.cast.cast)
    implementation(projects.cast.xlatorTest)
    xlatorTestDebugSharedLibraryConfig(projects.cast.xlatorTest)
    xlatorTestReleaseSharedLibraryConfig(projects.cast.xlatorTest)
  }

  binaries.whenElementFinalized {
    (this as CppExecutable)
        .linkTask
        .get()
        .configure(
            closureOf<LinkExecutable> {
              val libxlatorTest =
                  (if (isOptimized) xlatorTestReleaseSharedLibraryConfig
                      else xlatorTestDebugSharedLibraryConfig)
                      .singleFile
              addRpath(this, libxlatorTest)
              addCastLibrary(this@whenElementFinalized, this, project)

              if (isDebuggable && !isOptimized) {
                val checkSmokeMain by
                    tasks.registering(Exec::class) {
                      notCompatibleWithConfigurationCache(
                          "https://github.com/gradle/gradle/issues/13485")

                      // main executable to run for test
                      inputs.file(linkedFile)
                      doFirst { executable(linkedFile.get().asFile) }

                      // xlator Java bytecode + implementation of native methods
                      val pathElements = project.objects.listProperty<File>()
                      pathElements.addAll(files("../build/classes/java/test", libxlatorTest.parent))

                      // "primordial.txt" resource loaded during test
                      pathElements.add(coreResources.singleFile)
                      inputs.files(coreResources)

                      // additional supporting Java class files
                      inputs.files(smokeMainExtraPathElements)
                      pathElements.addAll(smokeMainExtraPathElements)

                      // all combined as a colon-delimited path list
                      argumentProviders.add { listOf(pathElements.get().joinToString(":")) }

                      // log output to file, although we don"t validate it
                      val outFile = project.layout.buildDirectory.file("${name}.log")
                      outputs.file(outFile)
                      doFirst {
                        outFile.get().asFile.outputStream().let {
                          standardOutput = it
                          errorOutput = it
                        }
                      }
                    }

                if (!(rootProject.extra["isWindows"] as Boolean)) {
                  // Known to be broken on Windows, but not intentionally so.  Please fix if you
                  // know how!  <https://github.com/wala/WALA/issues/608>
                  tasks.named("check").configure { dependsOn(checkSmokeMain) }
                }
              }
            })
  }
}
