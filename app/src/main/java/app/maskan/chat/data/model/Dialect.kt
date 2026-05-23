package app.maskan.chat.data.model

enum class Dialect(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val nativeName: String,
    val description: String
) {
    MSA(
        id = "msa",
        nameEn = "Modern Standard Arabic",
        nameAr = "العربية الفصحى",
        nativeName = "الفصحى",
        description = "Use clean MSA suitable for news, formal correspondence, or literary contexts."
    ),
    LEVANTINE(
        id = "levantine",
        nameEn = "Levantine",
        nameAr = "شامي",
        nativeName = "شامي",
        description = "Use widely-understood Levantine that works across Jordan, Syria, Lebanon, and Palestine. Avoid region-specific slang unless the user asks for a specific country variant. Strongly prefer native Arabic vocabulary over English or French loanwords — say سوق not ماركت, say سيارة not موتور, say مطعم not ريستوران, say هاتف or موبايل (acceptable) but never فون. Loanwords are only acceptable when there's truly no native equivalent or when the loanword has become more standard than the original."
    ),
    EGYPTIAN(
        id = "egyptian",
        nameEn = "Egyptian",
        nameAr = "مصري",
        nativeName = "مصري",
        description = "Use Cairene Egyptian Arabic — understood across the Arab world via media and film. Prefer native Arabic vocabulary over English loanwords where a natural Egyptian equivalent exists."
    ),
    GULF(
        id = "gulf",
        nameEn = "Gulf / Khaleeji",
        nameAr = "خليجي",
        nativeName = "خليجي",
        description = "Use widely-understood Khaleeji that works across Saudi, UAE, Kuwait, Qatar, Bahrain, and Oman. Avoid heavy region-specific vocabulary. Prefer native Arabic vocabulary over English loanwords — use the Arabic term when one is in common Gulf use."
    ),
    MAGHREBI(
        id = "maghrebi",
        nameEn = "Maghrebi",
        nameAr = "مغاربي",
        nativeName = "مغاربي",
        description = "Use Darija (Moroccan/Algerian/Tunisian) but flag any vocabulary that may not be understood by Mashriq Arabic speakers. Prefer native Arabic or established Darija vocabulary over French loanwords where a natural equivalent exists."
    );

    companion object {
        fun fromId(id: String): Dialect = entries.firstOrNull { it.id == id } ?: LEVANTINE
    }
}
