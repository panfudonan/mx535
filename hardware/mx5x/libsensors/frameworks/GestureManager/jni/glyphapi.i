%module glyphapi
%include "arrays_java.i"
%apply int[] {int *};
%apply short[] {unsigned char *}
%apply short[] {unsigned short *}


int MLAddGlyph(unsigned short GlyphID);
int MLBestGlyph(unsigned short *finalGesture);
int MLSetGlyphSpeedThresh(unsigned short speed);
int MLStartGlyph(void);
int MLStopGlyph(void);
int MLGetGlyph(int index, int *x, int *y);
int MLGetGlyphLength(unsigned short *length);
int MLClearGlyph(void);
int MLLoadGlyphs(unsigned char *libraryData);
int MLStoreGlyphs(unsigned char *libraryData, unsigned short *length);
int MLSetGlyphProbThresh(unsigned short prob);
int MLGetLibraryLength(unsigned short *length);

