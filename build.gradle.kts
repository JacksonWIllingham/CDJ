plugins {
    java
    maven
}

version = "0.1"

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    jcenter()
}

dependencies {
    implementation("org.apache.logging.log4j","log4j-api","2.13.1")
    implementation("org.apache.logging.log4j", "log4j-core", "2.13.1")

    implementation("net.dv8tion:JDA:4.1.1_135")

    implementation( "edu.cmu.sphinx","sphinx4-core","5prealpha-SNAPSHOT")
    implementation("edu.cmu.sphinx", "sphinx4-data", "5prealpha-SNAPSHOT")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_12
}