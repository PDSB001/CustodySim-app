import { execFileSync } from "node:child_process"

const archive = "build/distributions/CustodySim-Android-corresponding-source.zip"
const files = execFileSync("unzip", ["-Z1", archive], { encoding: "utf8" }).trim().split("\n")
for (const required of ["LICENSE", "gradlew", "gradle/wrapper/gradle-wrapper.jar", "app/build.gradle.kts", "episteme-core/UPSTREAM.json"]) {
  if (!files.includes(`CustodySim-app/${required}`)) throw new Error(`Source archive is missing ${required}`)
}
for (const file of files) {
  if (/(^|\/)(local\.properties|\.env[^/]*|\.git|\.gradle|\.idea)(\/|$)|\.(jks|keystore|pem|p12|apk|aab)$/i.test(file))
    throw new Error(`Private or generated file in source archive: ${file}`)
}
console.log(`Verified source archive with ${files.length} entries`)
