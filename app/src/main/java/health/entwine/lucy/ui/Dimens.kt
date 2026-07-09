// PD interaction dimension tokens — SRS v1.5 R-UXA-06 floors, R-UXA-14 scale.
// Evidence: PD_Interaction_Design_Principles_v1.md §B (motor-disabled accuracy
// keeps improving to ~30 mm targets; 48 dp is a floor, not a target).
package health.entwine.lucy.ui

import androidx.compose.ui.unit.dp

object PdDim {
    /** Primary push-to-talk control diameter (R-UXA-06: ≥ 180 dp). */
    val talkDiameter = 190.dp

    /** Every other interactive target's minimum edge (R-UXA-06: ≥ 75 dp). */
    val target = 76.dp

    /** Spacing between adjacent tappable targets (R-UXA-06: ≥ 50 dp). */
    val targetGap = 50.dp

    /**
     * Coequal 3-state motor row exception (SRS R-UXA-06 rationale): three
     * buttons sharing one row cannot keep 50 dp gaps on a 360 dp screen;
     * ≥ 20 dp gaps + ≥ 76 dp color-differentiated targets instead (Q-07
     * observation validates).
     */
    val rowGap = 20.dp

    /** Target/spacing multiplier while the user reports OFF/Shifting (R-UXA-14). */
    const val OFF_MODE_SCALE = 1.15f
}
