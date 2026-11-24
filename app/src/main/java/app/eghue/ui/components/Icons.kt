package app.eghue.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bathtub
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Deck
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.outlined.Yard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.graphics.vector.ImageVector
import app.eghue.hue.GroupKind

/** Maps Hue room archetypes to Material icons. */
fun groupIcon(archetype: String?, kind: GroupKind): ImageVector {
    if (kind == GroupKind.ZONE && archetype == null) return Icons.Outlined.Workspaces
    return when (archetype) {
        "living_room", "lounge" -> Icons.Outlined.Weekend
        "kitchen" -> Icons.Outlined.Kitchen
        "dining" -> Icons.Outlined.Restaurant
        "bedroom", "guest_room", "kids_bedroom" -> Icons.Outlined.Bed
        "bathroom", "toilet" -> Icons.Outlined.Bathtub
        "nursery" -> Icons.Outlined.ChildCare
        "office", "studio" -> Icons.Outlined.Work
        "computer" -> Icons.Outlined.Computer
        "gaming" -> Icons.Outlined.SportsEsports
        "tv", "recreation", "music" -> Icons.Outlined.Tv
        "hallway", "front_door" -> Icons.Outlined.DoorFront
        "staircase", "top_floor", "upstairs", "downstairs" -> Icons.Outlined.Stairs
        "garage", "carport", "driveway" -> Icons.Outlined.Garage
        "garden", "lawn", "porch", "pool" -> Icons.Outlined.Yard
        "terrace", "balcony", "barbecue" -> Icons.Outlined.Deck
        "gym" -> Icons.Outlined.FitnessCenter
        "laundry_room", "storage", "closet", "attic" -> Icons.Outlined.LocalLaundryService
        "reading", "man_cave" -> Icons.Outlined.Chair
        "home", "other", null -> if (kind == GroupKind.ZONE) Icons.Outlined.Workspaces else Icons.Outlined.Home
        else -> if (kind == GroupKind.ZONE) Icons.Outlined.Workspaces else Icons.Outlined.Home
    }
}

fun lightIcon(on: Boolean): ImageVector = if (on) Icons.Rounded.Lightbulb else Icons.Outlined.Lightbulb
