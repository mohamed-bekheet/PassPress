# generators is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either
# version 3 of the License, or (at your option) any later version.
#
# generators is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# generators. If not, see < http://www.gnu.org/licenses/ >.
#
# (C) The KiCad Librarian Team

from KicadModTree import Footprint, KicadFileHandler
from generators.tools.cli_args import CLI_ARGS


def write_footprint(
    kicad_mod: Footprint, library_name: str, generator_name: str
) -> None:
    """Save the footprint to the file system.

    Args:
        kicad_mod: The footprint.
        library_name: The name of the library.
        generator_name: The name of the generator.
    """
    if CLI_ARGS.dry_run:
        return
    if not library_name.endswith(".pretty"):
        library_name += ".pretty"
    if CLI_ARGS.separate_outputs:
        path = CLI_ARGS.output_dir_footprints / generator_name / library_name
    else:
        path = CLI_ARGS.output_dir_footprints / library_name
    path.mkdir(parents=True, exist_ok=True)

    # Delegate to the s-expression serialiser
    file_handler = KicadFileHandler(kicad_mod)
    file_handler.writeFile(path / (kicad_mod.name + ".kicad_mod"))
