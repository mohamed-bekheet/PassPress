# KicadModTree is free software: you can redistribute it and/or
# modify it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# KicadModTree is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with kicad-footprint-generator. If not, see < http://www.gnu.org/licenses/ >.
#
# (C) 2016 by Thomas Pointhuber, <thomas.pointhuber@gmx.at>
# (C) 2018 by Rene Poeschl, github @poeschlr
# (C) 2024 by C. Kuhlmann

from __future__ import division

from copy import copy
from math import sqrt
from sys import float_info

from KicadModTree.nodes.base.Pad import Pad
from KicadModTree.nodes.Node import Node
from KicadModTree.util.corner_handling import ChamferSizeHandler, RoundRadiusHandler
from kilibs.geom import CornerSelection, Vector2D
from kilibs.util.param_util import toVectorUseCopyIfNumber


class ChamferedNativePad(Node):
    r"""Add a ChamferedNativePad to the render tree

    This pad creates a native KiCad pad set to ``chamfered with other corners rounded``

    Some geometry (like non-45 degree corners and corners greater than halfthe shorter size)
    is not supported by the native KiCad pad. In this case use the ChamferedPad class,
    which creates a chamfered pad using custom primitives.

    :param \**kwargs:
        See below

    :Keyword Arguments:
        * *number* (``int``, ``str``) --
          number/name of the pad (default: \"\")
        * *type* (``Pad.TYPE_THT``, ``Pad.TYPE_SMT``, ``Pad.TYPE_CONNECT``, ``Pad.TYPE_NPTH``) --
          type of the pad
        * *at* (``Vector2D``) --
          center position of the pad
        * *rotation* (``float``) --
          rotation of the pad
        * *size* (``float``, ``Vector2D``) --
          size of the pad
        * *offset* (``Vector2D``) --
          offset of the pad
        * *drill* (``float``, ``Vector2D``) --
          drill-size of the pad
        * *solder_paste_margin_ratio* (``float``) --
          solder paste margin ratio of the pad (default: 0)
        * *solder_paste_margin* (``float``) --
          solder paste margin of the pad (default: 0)
        * *solder_mask_margin* (``float``) --
          solder mask margin of the pad (default: 0)
        * *layers* (``Pad.LAYERS_SMT``, ``Pad.LAYERS_THT``, ``Pad.LAYERS_NPTH``) --
          layers on which are used for the pad
        * *corner_selection* (``CornerSelection``) --
          Select which corner(s) to chamfer. (top left, top right, bottom right, bottom left)
        * *chamfer_size* (``float``, ``Vector2D``) --
          Size of the chamfer.
        * *x_mirror* (``[int, float](mirror offset)``) --
          mirror x direction around offset "point"
        * *y_mirror* (``[int, float](mirror offset)``) --
          mirror y direction around offset "point"

        * *round_radius_handler* (``RoundRadiusHandler``) --
          An instance of the RoundRadiusHandler class

        * *chamfer_size_handler* (``ChamferSizeHandler``) --
          An instance of the ChamferSizeHandler class
    """

    round_radius_handler: RoundRadiusHandler
    chamfer_size_handler: ChamferSizeHandler

    def __init__(self, **kwargs):
        Node.__init__(self)
        self._initPosition(**kwargs)
        self._initSize(**kwargs)
        self._initMirror(**kwargs)
        self._initPadSettings(**kwargs)

        self.pad = self._generatePad()

    def _initSize(self, **kwargs):
        if not kwargs.get("size"):
            raise KeyError('pad size not declared (like "size=[1,1]")')
        self.size = toVectorUseCopyIfNumber(kwargs.get("size"), low_limit=0)

    def _initPosition(self, **kwargs):
        if "at" not in kwargs:
            raise KeyError('center position not declared (like "at=[0,0]")')
        self.at = Vector2D(kwargs.get("at"))

    def _initMirror(self, **kwargs):
        self.mirror = {}
        if "x_mirror" in kwargs and type(kwargs["x_mirror"]) in [float, int]:
            self.mirror["x_mirror"] = kwargs["x_mirror"]
        if "y_mirror" in kwargs and type(kwargs["y_mirror"]) in [float, int]:
            self.mirror["y_mirror"] = kwargs["y_mirror"]

    def _initPadSettings(self, **kwargs):
        if "corner_selection" not in kwargs:
            raise KeyError(
                'corner selection is required for chamfered pads (like "corner_selection=[1,0,0,0]")'
            )

        self.corner_selection = CornerSelection(kwargs.get("corner_selection"))

        if "chamfer_size" not in kwargs:
            self.chamfer_size = Vector2D(0, 0)
        else:
            self.chamfer_size = toVectorUseCopyIfNumber(
                kwargs.get("chamfer_size"), low_limit=0, must_be_larger=False
            )

        self.round_radius_handler = kwargs["round_radius_handler"]
        self.chamfer_size_handler = kwargs["chamfer_size_handler"]

        self.padargs = copy(kwargs)
        self.padargs.pop("size", None)
        self.padargs.pop("shape", None)
        self.padargs.pop("at", None)
        self.padargs.pop("round_radius_handler", None)
        self.padargs.pop("chamfer_size_handler", None)

    def _generatePad(self):
        if self.chamfer_size[0] >= self.size[0] or self.chamfer_size[1] >= self.size[1]:
            raise ValueError(
                "Chamfer size ({}) too large for given pad size ({})".format(
                    self.chamfer_size, self.size
                )
            )

        is_chamfered = False
        if (
            self.corner_selection.is_any_selected()
            and self.chamfer_size[0] > 0
            and self.chamfer_size[1] > 0
        ):
            is_chamfered = True

        radius = self.round_radius_handler.get_round_radius(min(self.size))
        chamfer = self.chamfer_size_handler.get_chamfer_size(min(self.size))

        if is_chamfered:
            if (
                abs(self.chamfer_size[0] - self.chamfer_size[1])
                < 8 * float_info.epsilon
            ):
                pass
            else:
                raise ValueError(
                    "Native KiCad pads do not support non-45-degree chamfers yet. "
                    + f"Use KicadModTree.nodes.specialized.ChamferedPad to generate a "
                    + f"chamfered pad with custom primitives instead."
                )

            return Pad(
                at=self.at,
                shape=Pad.SHAPE_ROUNDRECT,
                size=self.size,
                chamfer_size_handler=self.chamfer_size_handler,
                chamfer_exact=chamfer,
                round_radius_handler=self.round_radius_handler,
                **self.padargs,
            )

        else:
            return Pad(
                at=self.at,
                shape=Pad.SHAPE_ROUNDRECT,
                size=self.size,
                round_radius_handler=self.round_radius_handler,
                **self.padargs,
            )

    @staticmethod
    def get_chamfer_to_avoid_circle(
        center: Vector2D,
        at: Vector2D,
        size: Vector2D,
        diameter: float | Vector2D,
        clearance: float = 0.0,
    ) -> Vector2D:
        """Set the chamfer such that the pad avoids a circle located at near corner.

        Args:
            center: The center of the circle to avoid.
            diameter: The diameter of the circle. If Vector2D given only the x-direction
                is used.
            clearance: Additional clearance around the circle.

        Returns:
            The chamfer dimensions such that the pad avoids the given circle.
        """
        relative_center = center - at
        # pad and circle are symmetric so we do not care which corner the
        # reference circle is located at.
        #  -> move it to bottom right to get only positive relative coordinates.
        relative_center.positive()
        d = diameter if isinstance(diameter, float | int) else diameter.x

        # Where should the chamfer be if the center of the reference circle
        # would be in line with the pad edges
        # (meaning exactly at the bottom right corner)
        reference_point = relative_center - sqrt(2) * (clearance + d / 2)
        chamfer_size = size / 2 - reference_point

        # compensate for reference circles not placed exactly at the corner
        edge_to_center = relative_center - size / 2
        chamfer_size -= [edge_to_center.y, edge_to_center.x]
        chamfer_size = Vector2D([x if x > 0 else 0 for x in chamfer_size])
        return chamfer_size

    def get_round_radius(self):
        return self.pad.get_round_radius()
