// Nameplate for a 3D printer, printed flat in two colours.
// The plate is one part (colour 1), the rim and letters are another (colour 2).
// Stick it on with double-sided mounting tape (e.g. 3M VHB) on a smooth, clean panel.
//
// Render one part at a time:
//   openscad -D 'name="ROSE"' -D 'part="base"' -o rose-base.stl nameplate.scad
//   openscad -D 'name="ROSE"' -D 'part="top"'  -o rose-top.stl  nameplate.scad

name      = "ROSE";
part      = "both";      // "base", "top" or "both" (preview)
font      = "Comfortaa:style=Bold";

width     = 110;         // mm
height    = 34;
corner    = 7;           // corner radius
base_h    = 2.4;         // plate thickness
raise     = 1.2;         // rim and letters stand this much above the plate
rim       = 2.2;         // rim width
text_size = 17;
spacing   = 1.12;

$fn = 64;

module rounded(w, h, r) {
    offset(r = r) square([w - 2 * r, h - 2 * r], center = true);
}

module base() {
    linear_extrude(base_h) rounded(width, height, corner);
}

module top() {
    translate([0, 0, base_h]) linear_extrude(raise) {
        difference() {                                   // rim
            rounded(width, height, corner);
            rounded(width - 2 * rim, height - 2 * rim, corner - rim);
        }
        text(name, size = text_size, font = font, halign = "center", valign = "center", spacing = spacing);
    }
}

if (part == "base") base();
else if (part == "top") top();
else { color("white") base(); color("#e05a8a") top(); }
