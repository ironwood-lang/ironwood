<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ten-leaf logo archive

This earlier option combines a capital I with ten leaves and symmetric circuit
branches. It is retained for reference. The active six-leaf logo is in `../../`.

- `ironwood-logo.png` is a 740 by 740 transparent white mark extracted directly
  from the user-supplied `image.png` reference, with permission to remove the
  baked-in checkerboard and caption using pixel cleanup.
- `ironwood-header-light.png` and `ironwood-header-dark.png` place that mark
  beside the title for the README. Both are transparent, 1560 by 720 pixels,
  and displayed at a maximum width of 780 pixels.

Header composition uses the logo at 608 by 608 pixels, positioned
at (64, 56). The three title lines are "The Ironwood", "Programming", and
"Language", set in Arial at weight 900 and 98 pixels. Their baselines are
(722, 282), (722, 395), and (722, 508). Text is `#1f2328` for light backgrounds
and `#f0f3f6` for dark backgrounds. The logo uses the matching title color in
each header, preserving its transparency and geometry.

## Extraction

The supplied reference is a 1152 by 918 opaque RGB image. A square crop at
(220, 63), measuring 740 by 740 pixels, keeps the logo and excludes the caption
and sparkle. The white artwork is separated from the gray checkerboard using
the minimum RGB channel value: values at or below 124 become transparent,
values at or above 245 become opaque, and intermediate values map linearly to
alpha. The mark's RGB channels are white. No logo geometry is redrawn.

ImageGen cleanup attempts were discarded. The README assets use only the
directly extracted reference artwork and deterministic title composition.
