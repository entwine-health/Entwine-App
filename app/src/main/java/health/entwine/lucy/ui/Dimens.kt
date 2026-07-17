// PD interaction dimension tokens — SRS v1.5 R-UXA-06 floors, R-UXA-14 scale.
// Evidence: PD_Interaction_Design_Principles_v1.md §B (motor-disabled accuracy
// keeps improving to ~30 mm targets; 48 dp is a floor, not a target).
package health.entwine.lucy.ui

import androidx.compose.ui.unit.dp

// R-UXA-14 (SRS v1.9): sizes are FIXED — the OFF/Shifting scale factor is gone.
// Rescaling live moved every control under the user's finger the moment they
// reported a motor state (owner field test, 2026-07-17); for a hand that is
// already failing, a target that moves is worse than a target that is smaller.
// The former enlarged geometry is now the permanent geometry, so OFF/Shifting
// targets are enlarged as the requirement demands — they just always were.
object PdDim {
    /** Primary push-to-talk control diameter (R-UXA-06: ≥ 180 dp; was 190 × 1.15). */
    val talkDiameter = 218.dp

    /** Every other interactive target's minimum edge (R-UXA-06: ≥ 75 dp; was 76 × 1.15). */
    val target = 87.dp

    /** Spacing between adjacent tappable targets (R-UXA-06: ≥ 50 dp). */
    val targetGap = 50.dp

    /**
     * Coequal 3-state motor row exception (SRS R-UXA-06 rationale): three
     * buttons sharing one row cannot keep 50 dp gaps on a 360 dp screen;
     * ≥ 20 dp gaps + ≥ 76 dp color-differentiated targets instead (Q-07
     * observation validates). 23 dp = the former 20 × 1.15.
     */
    val rowGap = 23.dp
}
