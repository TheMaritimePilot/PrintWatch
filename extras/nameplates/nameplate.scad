// Nameplates that hang on the top edge of a printer's glass door (no tape).
//
//   plate ("base" + "top")  printed flat, two colours: plate in one, rim + letters in the other
//   hook                    print 2 per plate, lying on its side (as exported); they hook over the
//                           top edge of the door glass and slide into two slots in the back of the plate
//   gauge                   test comb: slide it on the glass edge; the slot that fits snugly gives
//                           the glass thickness to use for `glass`
//
// Render a part:  openscad -D 'name="ROSE"' -D 'part="base"' -o rose-base.stl nameplate.scad

name      = "ROSE";
part      = "preview";   // base, top, hook, gauge, preview
font      = "Comfortaa:style=Bold";
glass     = 3.2;         // slot for the door glass, in mm: the P1S door glass measures 2.97 mm; a clip known
                         // to fit uses 3.0. Print the gauge if the hooks are loose or too tight

// plate
width     = 110;
height    = 34;
corner    = 7;
base_h    = 4.0;         // plate thickness (holds the slots)
raise     = 1.2;         // rim and letters stand this much above the plate
rim       = 2.2;
text_size = 17;
spacing   = 1.12;

// slots in the back of the plate for the hooks
slot_x    = 32;          // distance of each slot from the centre
slot_w    = 20.5;        // hooks are 20 mm wide
skin      = 0.8;         // plastic between slot and glass
slot_t    = 2.15;        // hook tab is 2.0 mm thick
slot_len  = 18.5;        // hook tab is 18 mm long

// hook (side profile, glass top edge at y = 0, outer glass face at x = 0)
clear     = 0.0;         // extra room around the glass (added to `glass`)
bump      = 0.2;         // optional small bump on the inner leg that presses on the glass
wall      = 1.5;         // thickness of the leg behind the glass (there is little room inside the door)
bridge    = 1.0;         // thickness over the top edge of the glass (the door must still close)
leg_in    = 7.5;         // how far the hook reaches down behind the glass
drop      = 4;           // the plate starts this far below the top of the glass
hook_w    = 20;
play      = 0.075;       // gap between the hook tab and the slot walls
tab       = 18;

$fn = 64;

module rounded(w, h, r) { offset(r = r) square([w - 2 * r, h - 2 * r], center = true); }

module plate_base() {
    difference() {
        linear_extrude(base_h) rounded(width, height, corner);
        for (sx = [-slot_x, slot_x])   // closed slots, open only at the top edge
            translate([sx - slot_w / 2, height / 2 - slot_len, skin]) cube([slot_w, slot_len + 1, slot_t]);
    }
}

module plate_top() {
    translate([0, 0, base_h]) linear_extrude(raise) {
        difference() {
            rounded(width, height, corner);
            rounded(width - 2 * rim, height - 2 * rim, corner - rim);
        }
        text(name, size = text_size, font = font, halign = "center", valign = "center", spacing = spacing);
    }
}

module hook_profile() {
    g = glass + clear;
    // back leg behind the glass, with a small bump that presses on the glass
    translate([-g - wall, -leg_in]) square([wall, leg_in + bridge]);
    if (bump > 0) translate([-g, -leg_in]) polygon([[0, 0], [bump, 1.2], [bump, 2.4], [0, 3.6]]);
    // bridge over the top edge of the glass
    front = skin + play + slot_t - 2 * play;              // outer face of the front leg
    translate([-g - wall, 0]) square([g + wall + front, bridge]);
    // front leg down to the plate, then the tab that slides into the plate
    translate([0, -drop]) square([front, drop + bridge]);
    translate([skin + play, -drop - tab]) square([slot_t - 2 * play, tab + 0.01]);
}

module hook() { linear_extrude(hook_w) hook_profile(); }

module gauge() {
    sizes  = [2.4, 2.6, 2.8, 3.0, 3.2, 3.4, 3.6];
    labels = ["2.4", "2.6", "2.8", "3.0", "3.2", "3.4", "3.6"];
    pitch = 9;
    difference() {
        translate([0, -12, 0]) cube([pitch * len(sizes) + 3, 22, 6]);
        for (i = [0 : len(sizes) - 1]) {
            translate([3 + i * pitch, -13, -1]) cube([sizes[i], 14, 8]);
            translate([3 + i * pitch + sizes[i] / 2, 6, 5.4])
                linear_extrude(1) text(labels[i], size = 2.6, halign = "center", valign = "center",
                                       font = "Liberation Sans:style=Bold");
        }
    }
}

if (part == "base") plate_base();
else if (part == "top") plate_top();
else if (part == "hook") hook();
else if (part == "gauge") gauge();
else if (part == "preview") {
    // preview: plate hanging on the hooks in front of the door glass
    // world: x = outward from the glass, y = along the door, z = up (top edge of the glass at 0)
    multmatrix([[0, 0, 1, 0], [1, 0, 0, 0], [0, 1, 0, -height / 2 - drop], [0, 0, 0, 1]]) {
        color("white") plate_base();
        color("#e05a8a") plate_top();
    }
    for (sx = [-slot_x, slot_x]) color("#e05a8a") translate([0, sx + hook_w / 2, 0]) rotate([90, 0, 0]) hook();
    color([0.55, 0.75, 0.9, 0.35]) translate([-glass, -75, -70]) cube([glass, 150, 70]);
}
