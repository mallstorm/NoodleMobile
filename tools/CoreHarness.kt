import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.selftest.CoreSelfTest
import java.io.File

fun main(args:Array<String>){
    val report=CoreSelfTest.run()
    report.checks.forEach{ println((if(it.passed) "PASS" else "FAIL")+" | "+it.name+(if(it.detail.isBlank())"" else " | "+it.detail)) }
    check(report.passed){"Core self-test failed"}
    val assetDir=File(args.firstOrNull() ?: "app/src/main/assets/adapters/bundled")
    val files=assetDir.walkTopDown().filter{it.isFile && it.name.equals("adapter.json",true)}.sortedBy{it.path}.toList()
    check(files.size==3){"Expected 3 bundled adapters, got ${files.size}"}
    for(file in files){
        val r=AdapterValidator.validate(file.readText(Charsets.UTF_8))
        println((if(r.isValid)"PASS" else "FAIL")+" | bundled adapter | "+file.parentFile.name+(if(r.isValid)"" else " | ${r.errorCode}: ${r.errorMessage}"))
        check(r.isValid){"Bundled adapter invalid: ${file.parentFile.name}: ${r.errorCode} ${r.errorMessage}"}
    }
}
