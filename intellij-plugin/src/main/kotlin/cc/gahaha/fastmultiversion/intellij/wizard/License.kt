package cc.gahaha.fastmultiversion.intellij.wizard

import java.time.Year

/**
 * The licenses a new mod can have, those the Minecraft Development plugin offers and CC BY 4.0: [id] goes into
 * fabric.mod.json and neoforge.mods.toml, and the text (`fastmultiversion/licenses/<id>.txt`) into the project's
 * LICENSE file.
 */
enum class License(val displayName: String, val id: String) {
    ALL_RIGHTS_RESERVED("All Rights Reserved", "All-Rights-Reserved"),
    MIT("MIT", "MIT"),
    ISC("Internet Systems Consortium (ISC) License", "ISC"),
    BSD_2("BSD 2-Clause (FreeBSD) License", "BSD-2-Clause-FreeBSD"),
    BSD_3("BSD 3-Clause (NewBSD) License", "BSD-3-Clause"),
    APACHE_2("Apache 2.0", "Apache-2.0"),
    MPL_2("Mozilla Public License 2.0", "MPL-2.0"),
    LGPL_3("GNU LGPL 3.0", "LGPL-3.0"),
    GPL_3("GNU GPL 3.0", "GPL-3.0"),
    AGPL_3("GNU AGPL 3.0", "AGPL-3.0"),
    UNLICENSE("Unlicense", "Unlicense"),
    CC_BY_4("Creative Commons Attribution 4.0 International", "CC-BY-4.0");

    /** The LICENSE file: the license's text, with the year and the copyright holder where it names them. */
    fun text(author: String, year: Int = Year.now().value): String {
        val text = License::class.java.getResourceAsStream("/fastmultiversion/licenses/$id.txt")
            ?.use { String(it.readAllBytes(), Charsets.UTF_8).replace("\r\n", "\n") }
            ?: throw IllegalStateException("missing the text of $id")
        return text.replace("{{year}}", year.toString()).replace("{{author}}", author)
    }

    override fun toString(): String = displayName
}
