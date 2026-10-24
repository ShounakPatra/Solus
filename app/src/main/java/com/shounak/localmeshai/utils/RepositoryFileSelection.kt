package com.shounak.localmeshai.utils

/** A file selection never removes the dependencies of a selected runnable export. */
internal data class RepositorySelectionPlan(
    val models: List<Pair<ModelVariant, RepoFile?>>,
    val extraFiles: List<RepoFile>,
    val automaticPaths: Set<String>,
    val downloadFiles: List<RepoFile>
)

internal object RepositoryFileSelection {
    fun visibleFiles(repo: ResolvedRepoInfo, input: ParsedHfInput): List<RepoFile> {
        val folder = input.path.orEmpty().trim('/')
        return repo.files.distinctBy { it.path }.filter {
            input.kind != HfInputKind.FOLDER || folder.isBlank() || it.path.startsWith("$folder/")
        }.sortedBy { it.path }
    }

    fun plan(repo: ResolvedRepoInfo, variants: List<ModelVariant>, selected: Set<String>): RepositorySelectionPlan {
        val paths = repo.files.map { it.path }.toSet()
        require(selected.all { it in paths }) { "Selected file is no longer in this repository revision" }
        val models = mutableListOf<Pair<ModelVariant, RepoFile?>>()
        val included = linkedMapOf<String, RepoFile>()
        // Prefer complete pipelines over their individual component exports.
        val candidates = variants.sortedByDescending { v -> v.companionFiles.any { it.fileName == "model_index.json" } }
        for (variant in candidates) {
            val pipeline = variant.companionFiles.any { it.fileName == "model_index.json" }
            val chosen = if (pipeline) variant.downloadFiles.any { it.format?.isPortable == true && it.path in selected }
                else variant.mainFiles.any { it.path in selected }
            if (!chosen || variant.mainFiles.all { it.path in included }) continue
            val explicitProjectors = variant.availableProjectors.filter { it.path in selected }
            val projector = explicitProjectors.firstOrNull { it == variant.projectorFile }
                ?: explicitProjectors.singleOrNull() ?: variant.projectorFile
            models += variant to projector
            (variant.downloadFiles + listOfNotNull(projector)).forEach { included[it.path] = it }
        }
        val extras = repo.files.filter { it.path in selected && it.path !in included }.distinctBy { it.path }
        extras.forEach { included[it.path] = it }
        return RepositorySelectionPlan(models, extras, included.keys - selected, included.values.toList())
    }
}
