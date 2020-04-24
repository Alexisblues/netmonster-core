package cz.mroczis.netmonster.core.feature.postprocess

import android.os.Build
import cz.mroczis.netmonster.core.model.annotation.SinceSdk
import cz.mroczis.netmonster.core.model.cell.CellLte
import cz.mroczis.netmonster.core.model.cell.ICell
import cz.mroczis.netmonster.core.model.config.PhysicalChannelConfig
import cz.mroczis.netmonster.core.model.connection.NoneConnection
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import cz.mroczis.netmonster.core.model.connection.SecondaryConnection

/**
 * EARFCN for LTE
 */
private typealias Channel = Int

/**
 * Bandwidth in kHz
 */
private typealias Bandwidth = Int

/**
 * Enriches data, works only for LTE.
 *
 * Basic abilities:
 *  - adds bandwidth to [PrimaryConnection], since [Build.VERSION_CODES.P]
 *  - adds bandwidth to [NoneConnection] and marks cell [SecondaryConnection], since [Build.VERSION_CODES.Q]
 */
@SinceSdk(Build.VERSION_CODES.P)
class PhysicalChannelPostprocessor(
    private val physicalChannelConfigGetter: (Int) -> List<PhysicalChannelConfig>
) : ICellPostprocessor {

    override fun postprocess(list: List<ICell>): List<ICell> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            list.groupBy { it.subscriptionId }
                .map { (subId, cells) ->
                    val configs = physicalChannelConfigGetter
                        .invoke(subId)
                        .toMutableList()

                    val res = MergeBundle(cells, configs)
                        .removeRedundantConfigs()
                        .mergeByPci()
                        .mergeByPciWithHints()
                        .mergeByPci()
                        .mergeByConnection()

                    res.cells

                }.flatten()
        } else {
            list
        }

    /**
     * Filtration round - remove configs that are useless. 
     * This is only applicable for [PrimaryConnection] cells who already have bandwidth.
     */
    private fun MergeBundle.removeRedundantConfigs() : MergeBundle {
        val usableConfigs = configs.filterNot { config ->
            cells.find { it is CellLte && it.connectionStatus == config.connectionStatus && it.bandwidth == config.bandwidth && (it.pci == config.pci || config.pci == null) } != null
        }
        
        return copy(configs = usableConfigs)
    }

    /**
     * We will try to find unique PCI in both cell list and configs list.
     * If we have that match then we'll merge data together.
     */
    private fun MergeBundle.mergeByPci(): MergeBundle {
        val cellToConfig = configs
            .filter { it.pci != null }
            .mapNotNull { config ->
                val candidates = cells
                    .filterIsInstance(CellLte::class.java)
                    .filter { cell -> cell.pci == config.pci && cell.bandwidth == null}

                if (candidates.size == 1) {
                    candidates[0] to config
                } else {
                    null
                }
            }.toMap()

        val mergedCells = cells.map { cell ->
            cellToConfig[cell]?.let { config ->
                mergeByCode(cell as CellLte, config)
            } ?: cell
        }

        val remainingConfigs = configs.toMutableList().apply {
            removeAll(cellToConfig.values)
        }.toList()

        return MergeBundle(
            cells = mergedCells,
            configs = remainingConfigs
        )
    }

    /**
     * Here we handle case when one PCI is used multiple times in different channels.
     * This method assumes that operator uses same bandwidth for given channel number which is
     * in most cases true.
     *
     * Example
     * -------
     * Cells:
     *  - A ... PCI = 10, EARFCN = 1850, BW = null
     *  - B ... PCI = 10, EARFCN = 6400, BW = null
     *  - C ... PCI = 20, EARFCN = 1850, BW = 20_000
     *
     * Configs:
     *  - X ... PCI = 10, BW = 10_000
     *  - Y ... PCI = 10, BW = 20_000
     *
     * Expected result is that Config Y will be merged with Cell A cause Cell C already has
     * BW filled and shares same EARFCN with Cell S
     */
    private fun MergeBundle.mergeByPciWithHints(): MergeBundle {
        val hints = bandwidthHints
        val cellToConfig = cells
            .filterIsInstance(CellLte::class.java)
            .mapNotNull { cell ->
                cell.band?.downlinkEarfcn
                    ?.let { hints[it] } // Get bandwidth that fits the most
                    ?.let { preferredBandwidth ->
                        val candidateConfigs =
                            configs.filter { it.bandwidth == preferredBandwidth && it.pci == cell.pci }

                        if (candidateConfigs.size == 1) {
                            cell to candidateConfigs[0]
                        } else {
                            null
                        }
                    }
            }.toMap()

        val mergedCells = cells.map { cell ->
            cellToConfig[cell]?.let { config ->
                mergeByCode(cell as CellLte, config)
            } ?: cell
        }

        val remainingConfigs = configs.toMutableList().apply {
            removeAll(cellToConfig.values)
        }.toList()

        return MergeBundle(
            cells = mergedCells,
            configs = remainingConfigs
        )
    }

    /**
     * Connection merging.
     * PCI is completely ignored since it's not available or it was already used in previous iterations.
     * Here we try ot match types of connections
     */
    private fun MergeBundle.mergeByConnection(): MergeBundle {
        val primaryConfigs = configs.filter { it.connectionStatus is PrimaryConnection }
        return if (primaryConfigs.size == 1) {
            val config = primaryConfigs[0]
            val merged = cells.map {
                if (it is CellLte && it.connectionStatus is PrimaryConnection) {
                    it.copy(bandwidth = it.bandwidth ?: config.bandwidth)
                } else {
                    it
                }
            }

            MergeBundle(
                cells = merged,
                configs = configs.toMutableList().apply {
                    remove(config)
                }.toList()
            )
        } else {
            // Cannot reliably handle multiple primary configs
            // Just skip this merging
            this
        }
    }

    /**
     * Merging by PCI, works since Android Q.
     *
     * Attempts to merge [cell] with [config].
     *  - Some phones do not return valid bandwidth using standard API, this method will attempt to fix
     * the issue
     *  - Some phones also indicate secondary connection to some cells, we take advantage of that
     */
    private fun mergeByCode(cell: CellLte, config: PhysicalChannelConfig): CellLte {
        val connection = when {
            // Keep primary
            cell.connectionStatus is PrimaryConnection ->
                cell.connectionStatus

            // Switch to secondary as reported by PhysicalChannel
            config.connectionStatus is SecondaryConnection ->
                config.connectionStatus

            // Samsung reports via PCC Primary connection for secondary cells, at least
            // that's what I saw on multiple devices, so keep it as a guess
            cell.connectionStatus is NoneConnection ->
                SecondaryConnection(isGuess = config.connectionStatus is PrimaryConnection)

            // Otherwise just keep it as it was
            else -> cell.connectionStatus
        }

        return cell.copy(
            bandwidth = cell.bandwidth ?: config.bandwidth,
            connectionStatus = connection
        )
    }

    /**
     * Internal structure for merging
     */
    private data class MergeBundle(
        /**
         * All cells that were scanned
         */
        val cells: List<ICell>,
        /**
         * Candidates that we want to use to enrich data in [cells]
         */
        val configs: List<PhysicalChannelConfig>
    ) {
        /**
         * Helper map that gives us information about already assigned bandwidths
         */
        val bandwidthHints: Map<Channel, Bandwidth>
            get() = cells.mapNotNull {
                if (it is CellLte && it.bandwidth != null && it.band?.channelNumber != null) {
                    it.band.channelNumber to it.bandwidth
                } else null
            }.toMap()
    }
}
