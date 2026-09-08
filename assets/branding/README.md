<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood logo

The logo combines a capital I with rounded outer corners, six leaves, and
symmetric circuit branches. There are three leaves on each side.

- `ironwood-logo.png` is a 740 by 740 transparent white mark.
- `ironwood-header-light.png` and `ironwood-header-dark.png` place the mark
  beside the title and place the slogan beneath the complete lockup for the
  README. Both are transparent, 1560 by 820 pixels, and displayed at a maximum
  width of 780 pixels.
- `archive/ten-leaves/` preserves the earlier ten-leaf option for reference.

Header composition uses the logo at 608 by 608 pixels, positioned at (64, 56).
The three title lines are "The Ironwood", "Programming", and "Language", set
in Arial at weight 900 and 98 pixels. Their baselines are (722, 282),
(722, 395), and (722, 508). The slogan, "The Java experience, built for native
performance.", is centered below the complete lockup in Arial Bold at 42
pixels, with a horizontal line on each side. The light artwork uses `#1f2328`;
the dark artwork uses `#f0f3f6`.

## Artwork preparation

The built-in ImageGen tool edited the user-supplied reference to reduce its
leaves from ten to six and round the I's outer corners. It produced a white
mark on a black background. Deterministic pixel cleanup converted the
background to alpha and normalized the artwork to the transparent logo.
The README headers use that same mark with deterministic title composition.

## Edit prompt

Edit the supplied Ironwood logo reference for ONE controlled design experiment. Keep the
same central white capital I with its large negative-space interior, the same symmetric
leaf-and-circuit visual language, balanced proportions and consistent line weight. Make
exactly these two changes: (1) Reduce the TEN leaves to exactly SIX leaves total, THREE on
the left and THREE on the right. On each side keep an upper leaf pointing diagonally
upward, a central leaf pointing horizontally outward, and a lower leaf pointing diagonally
downward. Remove the two intervening leaves on each side AND their now-unused connecting
branch segments, leaving more open negative space. Keep the small circular circuit nodes
and a few simple curved connectors in the reference style. (2) Round off the sharp corners
of the letter I, especially the outer corners of its top and bottom horizontal bars and
the sharp interior tips. Use gentle, visible corner radii while keeping the recognizable
capital I and its elegant concave sides. Preserve the wide negative-space interior. Do not
turn I into a solid stem or another letter. Do not add any leaves, symbols, ornaments,
gradients, metallic effects, outlines, shadows, or texture. Result must have EXACTLY SIX
leaf shapes, three per side, in perfect bilateral symmetry. Flat pure-white logo on a
perfectly uniform solid BLACK #000000 background, NOT transparency and NOT a checkerboard.
The black background will be removed deterministically later. Remove all caption text and
the bottom-right sparkle from the reference. Output only the centered logo with generous
even margin in a square image. No words, no mockup, no watermark.
