/*
 * Module: r2-streamer-kotlin
 * Developers: Quentin Gliosca
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.parser.epub

import org.readium.r2.shared.extensions.toMap
import org.readium.r2.shared.parser.xml.ElementNode
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.Collection
import org.readium.r2.shared.normalize
import org.readium.r2.shared.extensions.iso8601ToDate
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.Presentation

internal data class Title(
    val value: LocalizedString,
    val fileAs: LocalizedString? = null,
    val type: String? = null,
    val displaySeq: Int? = null
)

internal data class EpubLink(
    val href: String,
    val rels: Set<String>,
    val mediaType: String?, val refines: String?,
    val properties: List<String> = emptyList()
)

internal data class EpubMetadata(
    val global: Map<String, List<MetadataItem>>,
    val refine: Map<String, Map<String, List<MetadataItem>>>,
    val links: List<EpubLink>
)

internal class MetadataParser(private val epubVersion: Double, private val prefixMap: Map<String, String>) {

    fun parse(document: ElementNode, filePath: String): EpubMetadata? {
        val metadata = document.getFirst("metadata", Namespaces.OPF)
            ?: return null
        val (metas, links) = parseElements(metadata, filePath)
        val (globalMetas, refineMetas) = resolveMetaHierarchy(metas).partition { it.refines == null }
        val globalCollection = globalMetas.groupBy(MetadataItem::property)
        @Suppress("Unchecked_cast")
        val refineCollections = (refineMetas.groupBy(MetadataItem::refines) as Map<String, List<MetadataItem>>)
            .mapValues { it.value.groupBy(MetadataItem::property) }
        return EpubMetadata(globalCollection, refineCollections, links)
    }

    private fun parseElements(metadataElement: ElementNode, filePath: String): Pair<List<MetadataItem>, List<EpubLink>> {
        val metas: MutableList<MetadataItem> = mutableListOf()
        val links: MutableList<EpubLink> = mutableListOf()
        for (e in metadataElement.getAll()) {
            when {
                e.namespace == Namespaces.DC ->
                    parseDcElement(e)?.let { metas.add(it) }
                e.namespace == Namespaces.OPF && e.name == "meta" ->
                    parseMetaElement(e)?.let { metas.add(it) }
                e.namespace == Namespaces.OPF && e.name == "link" ->
                    parseLinkElement(e, filePath)?.let { links.add(it) }
            }
        }
        return Pair(metas, links)
    }

    private fun parseLinkElement(element: ElementNode, filePath: String): EpubLink? {
        val href = element.getAttr("href") ?: return null
        val relAttr = element.getAttr("rel").orEmpty()
        val rel = parseProperties(relAttr).mapNotNull { resolveProperty(it, prefixMap, DEFAULT_VOCAB.LINK) }
        val propAttr = element.getAttr("properties").orEmpty()
        val properties = parseProperties(propAttr).mapNotNull { resolveProperty(it, prefixMap, DEFAULT_VOCAB.LINK) }
        val mediaType = element.getAttr("media-type")
        val refines = element.getAttr("refines")?.removePrefix("#")
        return EpubLink(normalize(filePath, href), rel.toSet(), mediaType, refines, properties)
    }

    private fun parseMetaElement(element: ElementNode): MetadataItem? {
        return if (element.getAttr("property") == null) {
            val name = element.getAttr("name")
                ?: return null
            val content = element.getAttr("content")
                ?: return null
            MetadataItem(name, content, element.lang, null, null, element.id)
        } else {
            val propName = element.getAttr("property")?.trim()?.ifEmpty { null }
                ?: return null
            val propValue = element.text?.trim()?.ifEmpty { null }
                ?: return null
            val resolvedProp = resolveProperty(propName, prefixMap, DEFAULT_VOCAB.META)
                ?: return null
            val resolvedScheme =
                element.getAttr("scheme")?.trim()?.ifEmpty { null }?.let { resolveProperty(it, prefixMap) }
            val refines = element.getAttr("refines")?.removePrefix("#")
            MetadataItem(resolvedProp, propValue, element.lang, resolvedScheme, refines, element.id)
        }
    }

    private fun parseDcElement(element: ElementNode): MetadataItem? {
        val propValue = element.text?.trim()?.ifEmpty { null } ?: return null
        val propName = Vocabularies.DCTERMS + element.name
        return when (element.name) {
            "creator", "contributor", "publisher" -> contributorWithLegacyAttr(element, propName, propValue)
            "date" -> dateWithLegacyAttr(element, propName, propValue)
            else -> MetadataItem(propName, propValue, lang = element.lang, id = element.id)
        }
    }

    private fun contributorWithLegacyAttr(element: ElementNode, name: String, value: String): MetadataItem {
        val fileAs = element.getAttrNs("file-as", Namespaces.OPF)?.let {
            MetadataItem(Vocabularies.META + "file-as", value = it, lang = element.lang, id = element.id)
        }
        val role = element.getAttrNs("role", Namespaces.OPF)?.let {
            MetadataItem(Vocabularies.META + "role", it, lang = element.lang, id = element.id)
        }
        val children = listOfNotNull(fileAs, role).groupBy(MetadataItem::property)
        return MetadataItem(name, value, lang = element.lang, id = element.id, children = children)
    }

    private fun dateWithLegacyAttr(element: ElementNode, name: String, value: String): MetadataItem? {
        val eventAttr = element.getAttrNs("event", Namespaces.OPF)
        val propName = if (eventAttr == "modification") Vocabularies.DCTERMS + "modified" else name
        return MetadataItem(propName, value, lang = element.lang, id = element.id)
    }

    private fun resolveMetaHierarchy(items: List<MetadataItem>): List<MetadataItem> {
        val metadataIds = items.mapNotNull { it.id }
        val rootExpr = items.filter { it.refines == null || it.refines !in metadataIds }
        @Suppress("Unchecked_cast")
        val exprByRefines = items.groupBy(MetadataItem::refines) as Map<String, List<MetadataItem>>
        return rootExpr.map { computeMetaItem(it, exprByRefines, emptySet()) }
    }

    private fun computeMetaItem(expr: MetadataItem, metas: Map<String, List<MetadataItem>>, chain: Set<String>): MetadataItem {
        val updatedChain = if (expr.id == null) chain else chain + expr.id
        val refinedBy = expr.id?.let { metas[it] }?.filter { it.id !in chain }.orEmpty()
        val newChildren = refinedBy.map { computeMetaItem(it, metas, updatedChain) }
        return expr.copy(children = (expr.children.values.flatten() + newChildren).groupBy(MetadataItem::property))
    }
}

internal open class MetadataAdapter(val epubVersion: Double, val items: Map<String, List<MetadataItem>>) {
    val duration = firstValue(Vocabularies.MEDIA + "duration")?.let { ClockValueParser.parse(it) }

    protected fun firstValue(property: String) = items[property]?.firstOrNull()?.value
}

internal class LinkMetadataAdapter(
    epubVersion: Double,
    items: Map<String, List<MetadataItem>>
) : MetadataAdapter(epubVersion, items)

internal class PubMetadataAdapter(
    epubVersion: Double,
    items: Map<String, List<MetadataItem>>,
    fallbackTitle: String,
    uniqueIdentifierId: String?,
    readingProgression: ReadingProgression,
    displayOptions: Map<String, String>
) : MetadataAdapter(epubVersion, items) {

    fun metadata() = Metadata(
        identifier = identifier,
        modified = modified,
        published = published,
        languages = languages,
        localizedTitle = localizedTitle,
        localizedSortAs = localizedSortAs,
        localizedSubtitle = localizedSubtitle,
        duration = duration,
        subjects = subjects,
        description = description,
        readingProgression = readingProgression,
        belongsToCollections = belongsToCollections,
        belongsToSeries = belongsToSeries,
        otherMetadata = otherMetadata,

        authors = contributors("aut"),
        translators = contributors("trl"),
        editors = contributors("edt"),
        publishers = contributors("pbl"),
        artists = contributors("art"),
        illustrators = contributors("ill"),
        colorists = contributors("clr"),
        narrators = contributors("nrt"),
        contributors = contributors(null)
    )

    private val defaultLang = firstValue(Vocabularies.DCTERMS + "language")

    val languages = items[Vocabularies.DCTERMS + "language"]?.map(MetadataItem::value).orEmpty()

    val identifier: String?

    init {
        val identifiers = items[Vocabularies.DCTERMS + "identifier"]
            ?.associate { Pair(it.property, it.value) }.orEmpty()

        identifier = uniqueIdentifierId?.let { identifiers[it] } ?: identifiers.values.firstOrNull()
    }

    val published = firstValue(Vocabularies.DCTERMS + "date")?.iso8601ToDate()

    val modified = firstValue(Vocabularies.DCTERMS + "modified")?.iso8601ToDate()

    val description = firstValue(Vocabularies.DCTERMS + "description")

    val cover = firstValue("cover")

    val localizedTitle: LocalizedString

    val localizedSubtitle: LocalizedString?

    val localizedSortAs: LocalizedString?

    init {
        val titles = items[Vocabularies.DCTERMS + "title"]?.map { it.toTitle(defaultLang) }.orEmpty()
        val mainTitle = titles.firstOrNull { it.type == "main" } ?: titles.firstOrNull()

        localizedTitle =  mainTitle?.value ?: LocalizedString(fallbackTitle)
        localizedSubtitle = titles.filter { it.type == "subtitle" }.sortedBy(Title::displaySeq).firstOrNull()?.value
        localizedSortAs = mainTitle?.fileAs ?: firstValue("calibre:title_sort")?.let { LocalizedString(it) }
    }

    val belongsToSeries: List<Collection>

    val belongsToCollections: List<Collection>

    init {
        if (epubVersion < 3.0) {
            val calibreSeries = items["calibre:series"]?.firstOrNull()?.let {
                val name = LocalizedString.fromStrings(mapOf(it.lang to it.value))
                val position = firstValue("calibre:series_index")?.toDoubleOrNull()
                Collection(localizedName = name, position = position)
            }

            belongsToSeries = listOfNotNull(calibreSeries)
            belongsToCollections = emptyList()

        } else {
            val allCollections = items[Vocabularies.META + "belongs-to-collection"]
                .orEmpty().map { it.toCollection(defaultLang) }
            val (seriesMeta, collectionsMeta) = allCollections.partition { it.first == "series" }

            belongsToSeries = seriesMeta.map(Pair<String?, Collection>::second)
            belongsToCollections = collectionsMeta.map(Pair<String?, Collection>::second)
        }
    }

    val subjects: List<Subject>

    init {
        val subjectItems = items[Vocabularies.DCTERMS + "subject"].orEmpty()
        val parsedSubjects = subjectItems.map { it.toSubject(defaultLang) }
        val hasToSplit = parsedSubjects.size == 1 && parsedSubjects.first().run {
            localizedName.translations.size == 1 && code == null && scheme == null && sortAs == null
        }

        subjects = if (hasToSplit) splitSubject(parsedSubjects.first()) else parsedSubjects
    }

    private fun splitSubject(subject: Subject): List<Subject> {
        val lang = subject.localizedName.translations.keys.first()
        val names = subject.localizedName.translations.values.first().string.split(",", ";")
            .map(kotlin.String::trim).filter(kotlin.String::isNotEmpty)
        return names.map {
            val newName = LocalizedString.fromStrings(mapOf(lang to it))
            Subject(localizedName = newName)
        }
    }

    private val allContributors: Map<String?, List<Contributor>>

    init {
        val creators = items[Vocabularies.DCTERMS + "creator"].orEmpty()
            .map { it.toContributor(defaultLang, "aut") }
        val publishers = items[Vocabularies.DCTERMS + "publisher"].orEmpty()
            .map { it.toContributor(defaultLang, "pbl") }
        val others = items[Vocabularies.DCTERMS + "contributor"].orEmpty()
            .map { it.toContributor(defaultLang) }
        val narrators = items[Vocabularies.MEDIA + "narrator"].orEmpty()
            .map { it.toContributor(defaultLang, "nrt") }
        val contributors = creators + publishers + narrators + others
        val knownRoles = setOf("aut", "trl", "edt", "pbl", "art", "ill", "clr", "nrt")
        allContributors = contributors.distributeBy(knownRoles, Contributor::roles)
    }

    private fun <K, V> List<V>.distributeBy(classes: Set<K>, transform: (V) -> kotlin.collections.Collection<K>): Map<K?, List<V>> {
        /* Map all elements with [transform] and compute a [Map] with keys [null] and elements from [classes] and,
         as values, lists of elements whose transformed values contain the key.
         If a transformed element is in no class, it is assumed to be in [null] class. */

        val map: MutableMap<K?, MutableList<V>> = mutableMapOf()
        for (element in this) {
            val transformed = transform(element).filter { it in classes }
            if (transformed.isEmpty())
                map.getOrPut(null) { mutableListOf() }.add(element)
            for (v in transformed)
                map.getOrPut(v) { mutableListOf() }.add(element)
        }
        return map
    }

    fun contributors(role: String?) = allContributors[role].orEmpty()

    val readingProgression = readingProgression

    val presentation: Presentation

    init {
        val flowProp = firstValue(Vocabularies.RENDITION + "flow")
        val spreadProp = firstValue(Vocabularies.RENDITION + "spread")
        val orientationProp = firstValue(Vocabularies.RENDITION + "orientation")
        val layoutProp =
            if (epubVersion < 3.0)
                if (displayOptions["fixed-layout"] == "true") "pre-paginated" else "reflowable"
            else firstValue(Vocabularies.RENDITION + "layout")

        val (overflow, continuous) = when (flowProp) {
            "paginated" -> Pair(Presentation.Overflow.PAGINATED, false)
            "scrolled-continuous" -> Pair(Presentation.Overflow.SCROLLED, true)
            "scrolled-doc" -> Pair(Presentation.Overflow.SCROLLED, false)
            else -> Pair(Presentation.Overflow.AUTO, false)
        }

        val layout = when (layoutProp) {
            "pre-paginated" -> EpubLayout.FIXED
            else -> EpubLayout.REFLOWABLE
        }

        val orientation = when (orientationProp) {
            "landscape" -> Presentation.Orientation.LANDSCAPE
            "portrait" -> Presentation.Orientation.PORTRAIT
            else -> Presentation.Orientation.AUTO
        }

        val spread = when (spreadProp) {
            "none" -> Presentation.Spread.NONE
            "landscape" -> Presentation.Spread.LANDSCAPE
            "both", "portrait" -> Presentation.Spread.BOTH
            else -> Presentation.Spread.AUTO
        }

        presentation = Presentation(
            overflow = overflow, continuous = continuous,
            layout = layout, orientation = orientation, spread = spread
        )
    }

    val otherMetadata: Map<String, Any>

    init {
        val dcterms = listOf(
            "identifier", "language", "title", "date", "modified", "description",
            "duration", "creator", "publisher", "contributor"
        ).map { Vocabularies.DCTERMS + it }
        val media = listOf("narrator", "duration").map { Vocabularies.MEDIA + it }
        val rendition = listOf("flow", "spread", "orientation", "layout").map { Vocabularies.RENDITION + it }
        val usedProperties: List<String> = dcterms + media + rendition

        val otherMap: MutableMap<String, Any> = mutableMapOf()
        val others = items.filterKeys { it !in usedProperties }.values.flatten()
        others.forEach { otherMap[it.property] = it.toMap() }
        otherMetadata = otherMap + Pair("presentation", presentation.toJSON().toMap())
    }
}

internal data class MetadataItem(
    val property: String,
    val value: String,
    val lang: String,
    val scheme: String? = null,
    val refines: String? = null,
    val id: String?,
    val children: Map<String, List<MetadataItem>> = emptyMap()
) {

    fun toSubject(defaultLang: String?): Subject {
        require(property == Vocabularies.DCTERMS + "subject")
        val values = localizedString(defaultLang)
        val localizedSortAs = fileAs?.let { LocalizedString(it.second, if (it.first == "") defaultLang else it.first) }
        return Subject(values, localizedSortAs, authority, term)
    }

    fun toTitle(defaultLang: String?): Title {
        require(property == Vocabularies.DCTERMS + "title")
        val values = localizedString(defaultLang)
        val localizedSortAs = fileAs?.let { LocalizedString(it.second, if (it.first == "") defaultLang else it.first) }
        return Title(values, localizedSortAs, titleType, displaySeq)
    }

    fun toContributor(defaultLang: String?, defaultRole: String? = null): Contributor {
        require(property in listOf("creator", "contributor", "publisher").map { Vocabularies.DCTERMS + it } +
                (Vocabularies.MEDIA + "narrator") + (Vocabularies.META + "belongs-to-collection"))
        val names = localizedString(defaultLang)
        val localizedSortAs = fileAs?.let { LocalizedString(it.second, if (it.first == "") defaultLang else it.first) }
        return Contributor(names, localizedSortAs = localizedSortAs,
            roles = roles(defaultRole), identifier = identifier, position = groupPosition)
    }

    fun toCollection(defaultLang: String?) = Pair(collectionType, toContributor(defaultLang))

    fun toMap(): Any =
        if (children.isEmpty())
            value
        else {
            val mappedChildren = children.values.flatten().associate { Pair(it.property, it.toMap()) }
            mappedChildren + Pair("@value", value)
        }

    private val fileAs
        get() = children[Vocabularies.META + "file-as"]?.firstOrNull()?.let { Pair(it.lang, it.value) }

    private val titleType
        get() = firstValue(Vocabularies.META + "title-type")

    private val displaySeq
        get() = firstValue(Vocabularies.META + "display-seq")?.toIntOrNull()

    private val authority
        get() = firstValue(Vocabularies.META + "authority")

    private val term
        get() = firstValue(Vocabularies.META + "term")

    private val alternateScript
        get() = children[Vocabularies.META + "alternate-script"]?.associate { Pair(it.lang, it.value) }.orEmpty()

    private val collectionType
        get() = firstValue(Vocabularies.META + "collection-type")

    private val groupPosition
        get() = firstValue(Vocabularies.META + "group-position")?.toDoubleOrNull()

    private val identifier
        get() = firstValue(Vocabularies.DCTERMS + "identifier")

    private fun localizedString(defaultLang: String?): LocalizedString {
        val values = mapOf(lang to value).plus(alternateScript)
            .mapKeys { if (it.key.isEmpty()) defaultLang else it.key }
        return LocalizedString.fromStrings(values)
    }

    private fun roles(default: String?): Set<String> {
        val roles = allValues(Vocabularies.META + "role")
        return if (roles.isEmpty() && default != null) setOf(default) else roles.toSet()
    }

    private fun firstValue(property: String) = children[property]?.firstOrNull()?.value

    private fun allValues(property: String) = children[property]?.map(MetadataItem::value).orEmpty()
}
