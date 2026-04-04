#!/usr/bin/env node
/**
 * Transition Data Quality Comparison Script
 * Compares transitions.json against reference HTML files
 */

const fs = require('fs');
const path = require('path');

// ─── Load data ───────────────────────────────────────────────────────
const transitionsPath = 'D:/SnowsDecoder/Walkability/transitions.json';
const teleportRefPath = 'D:/SnowsDecoder/zReference_Files/Travel_Teleportation_Reference.html';
const traversalRefPath = 'D:/SnowsDecoder/zReference_Files/Traversal_Objects_Reference.html';

const raw = JSON.parse(fs.readFileSync(transitionsPath, 'utf8'));
const transitions = raw.transitions;
const teleportHTML = fs.readFileSync(teleportRefPath, 'utf8');
const traversalHTML = fs.readFileSync(traversalRefPath, 'utf8');

console.log(`Total transitions loaded: ${transitions.length}`);
console.log('');

// ─── Helper: Parse coordinates from HTML ─────────────────────────────
function parseCoord(str) {
  // Parse "3297, 3185, 0" from <span class="coord">3297, 3185, 0</span>
  const m = str.match(/(\d+),\s*(\d+),\s*(\d+)/);
  if (!m) return null;
  return { x: parseInt(m[1]), y: parseInt(m[2]), p: parseInt(m[3]) };
}

function coordStr(x, y, p) {
  return `(${x}, ${y}, ${p})`;
}

function isInstanceSpace(x, y) {
  // Instance-space coordinates typically have very high values or specific ranges
  // The user says these have src coordinates like (247, 4235+)
  return (x >= 200 && x <= 300 && y >= 4200);
}

// ─── Reference data (hardcoded from HTML to avoid regex issues) ──────
function getReferenceLodestones() {
  // 10 F2P + 19 Members + 3 Seasonal = 32 total
  return {
    // F2P (10)
    'Al Kharid':           { x: 3297, y: 3185, p: 0 },
    'Ashdale':             { x: 2474, y: 2709, p: 2 },
    'Burthorpe':           { x: 2899, y: 3545, p: 0 },
    'Draynor':             { x: 3105, y: 3299, p: 0 },
    'Edgeville':           { x: 3067, y: 3506, p: 0 },
    'Falador':             { x: 2967, y: 3404, p: 0 },
    'Lumbridge':           { x: 3233, y: 3222, p: 0 },
    'Port Sarim':          { x: 3011, y: 3216, p: 0 },
    'Taverley':            { x: 2878, y: 3443, p: 0 },
    'Varrock':             { x: 3214, y: 3377, p: 0 },
    // Members (19)
    'Anachronia':          { x: 5431, y: 2339, p: 0 },
    'Ardougne':            { x: 2634, y: 3349, p: 0 },
    'Bandit Camp':         { x: 3214, y: 2955, p: 0 },
    'Canifis':             { x: 3517, y: 3516, p: 0 },
    'Catherby':            { x: 2811, y: 3450, p: 0 },
    "Eagles' Peak":        { x: 2366, y: 3480, p: 0 },
    'Fremennik Province':  { x: 2712, y: 3678, p: 0 },
    'Karamja':             { x: 2761, y: 3148, p: 0 },
    'Lunar Isle':           { x: 2088, y: 3912, p: 0 },
    'Menaphos':            { x: 3216, y: 2717, p: 0 },
    "Oo'glog":             { x: 2532, y: 2872, p: 0 },
    'Prifddinas':          { x: 2208, y: 3361, p: 1 },
    "Seers' Village":      { x: 2689, y: 3483, p: 0 },
    'Tirannwn':            { x: 2254, y: 3150, p: 0 },
    'Wilderness Crater':   { x: 3143, y: 3636, p: 0 },
    'Yanille':             { x: 2529, y: 3095, p: 0 },
    'Fort Forinthry':      { x: 3298, y: 3526, p: 0 },
    'City of Um':          { x: 1083, y: 1768, p: 1 },
    'Wendlewick':          { x: 3461, y: 1520, p: 0 },
    // Seasonal (3)
    'Christmas Village':   { x: 5219, y: 9821, p: 0 },
    'Blooming Burrow':     { x: 3806, y: 4987, p: 0 },
    'Harvest Hollow':      { x: 608, y: 1705, p: 0 },
  };
}

function extractFairyRingCodes(html) {
  const codes = [];
  const sectionMatch = html.match(/id="fairy-rings"[\s\S]*?<\/div>\s*<\/div>\s*<\/div>/);
  if (!sectionMatch) return codes;
  const section = sectionMatch[0];

  const codeRegex = /<span class="code">([A-Z]{3})<\/span><\/td>\s*<td>([^<]+)/g;
  let m;
  while ((m = codeRegex.exec(section)) !== null) {
    codes.push({ code: m[1], destination: m[2].trim() });
  }
  return codes;
}

function extractSpiritTrees(html) {
  const trees = [];
  const sectionMatch = html.match(/id="spirit-trees"[\s\S]*?<\/div>\s*<\/div>/);
  if (!sectionMatch) return trees;
  const section = sectionMatch[0];

  const rowRegex = /<tr>\s*<td>([^<]+)<\/td>\s*<td><span class="coord">([^<]+)<\/span><\/td>/g;
  let m;
  while ((m = rowRegex.exec(section)) !== null) {
    const name = m[1].replace(/&rsquo;/g, "'");
    const coord = parseCoord(m[2]);
    if (coord) {
      trees.push({ name, ...coord });
    }
  }
  return trees;
}

function extractGliders(html) {
  const gliders = [];
  const sectionMatch = html.match(/id="gliders"[\s\S]*?<\/div>\s*<\/div>/);
  if (!sectionMatch) return gliders;
  const section = sectionMatch[0];

  const rowRegex = /<tr>\s*<td>([^<]+)<\/td>\s*<td>([^<]+)<\/td>\s*<td><span class="coord">([^<]+)<\/span><\/td>/g;
  let m;
  while ((m = rowRegex.exec(section)) !== null) {
    const codeName = m[1].trim();
    const location = m[2].replace(/&rsquo;/g, "'").trim();
    const coord = parseCoord(m[3]);
    gliders.push({ codeName, location, coord });
  }
  return gliders;
}

function extractItemTeleports(html) {
  const items = {};

  // Extract jewelry section
  const jewelryMatch = html.match(/id="jewelry"[\s\S]*?<\/div>\s*<\/div>/);
  if (jewelryMatch) {
    const section = jewelryMatch[0];
    // Extract each card's h4 name and destinations
    const cardRegex = /<h4>([^<]+)<\/h4>[\s\S]*?(?=<div class="card|<\/div>\s*<\/div>)/g;
    let cm;
    while ((cm = cardRegex.exec(section)) !== null) {
      const itemName = cm[1].replace(/&rsquo;/g, "'").replace(/&ndash;/g, "-");
      const cardText = cm[0];
      const dests = [];
      const destRegex = /<tr>\s*<td>([^<]+)<\/td>\s*<td><span class="coord">([^<]+)<\/span><\/td>/g;
      let dm;
      while ((dm = destRegex.exec(cardText)) !== null) {
        const destName = dm[1].replace(/&rsquo;/g, "'");
        const coord = parseCoord(dm[2]);
        if (coord) dests.push({ name: destName, ...coord });
      }
      if (dests.length > 0) {
        items[itemName] = dests;
      }
    }
  }

  // Extract special items section
  const specialMatch = html.match(/id="special-items"[\s\S]*?<\/div>\s*<\/div>/);
  if (specialMatch) {
    const section = specialMatch[0];
    // Get items with coordinates from the main table
    const rowRegex = /<td>[^<]*(?:<span[^>]*>[^<]*<\/span>[^<]*)*<\/td>\s*<td>([^<]+)<\/td>\s*<td>[^<]*<\/td>\s*<td><span class="coord">([^<]+)<\/span>/g;
    let rm;
    while ((rm = rowRegex.exec(section)) !== null) {
      const name = rm[1].replace(/&rsquo;/g, "'").replace(/&ndash;/g, "-");
      // Parse possibly multiple coords separated by /
      const coordStr = rm[2];
      const coords = [];
      const coordParts = coordStr.split('/');
      for (const part of coordParts) {
        const c = parseCoord(part);
        if (c) coords.push(c);
      }
      if (coords.length > 0 && !items[name]) {
        items[name] = coords.map(c => ({ name, ...c }));
      }
    }
  }

  // Extract tablets
  const tabletsMatch = html.match(/id="tablets"[\s\S]*?<\/div>\s*<\/div>/);
  if (tabletsMatch) {
    const section = tabletsMatch[0];
    const tabletRegex = /<td>[^<]*(?:<span[^>]*>[^<]*<\/span>)?[^<]*<\/td>\s*<td>([^<]+)<\/td>\s*<td>[^<]*<\/td>\s*<td><span class="coord">(\d+,\s*\d+,\s*\d+)<\/span>/g;
    let tm;
    while ((tm = tabletRegex.exec(section)) !== null) {
      const name = tm[1].replace(/&rsquo;/g, "'").replace(/&ndash;/g, "-");
      const coord = parseCoord(tm[2]);
      if (coord && !items[name]) {
        items['Tablet: ' + name] = [{ name, ...coord }];
      }
    }
  }

  // Extract diary rewards
  const diaryMatch = html.match(/id="diary-rewards"[\s\S]*?<\/div>\s*<\/div>/);
  if (diaryMatch) {
    const section = diaryMatch[0];
    const diaryRegex = /<td>([^<]+)<\/td>\s*<td>[^<]*(?:<span[^>]*>[^<]*<\/span>[^<]*)*<\/td>\s*<td>[^<]*<\/td>\s*<td><span class="coord">([^<]+)<\/span>/g;
    let drm;
    while ((drm = diaryRegex.exec(section)) !== null) {
      const name = drm[1].replace(/&rsquo;/g, "'");
      const coord = parseCoord(drm[2]);
      if (coord && !items[name]) {
        items['Diary: ' + name] = [{ name, ...coord }];
      }
    }
  }

  return items;
}

// ═══════════════════════════════════════════════════════════════════════
// 1. LODESTONES
// ═══════════════════════════════════════════════════════════════════════
console.log('='.repeat(80));
console.log('1. LODESTONE ANALYSIS');
console.log('='.repeat(80));

const refLodestones = getReferenceLodestones();
console.log(`\nReference lodestones found: ${Object.keys(refLodestones).length}`);
for (const [name, coord] of Object.entries(refLodestones)) {
  console.log(`  ${name}: ${coordStr(coord.x, coord.y, coord.p)}`);
}

// Filter our lodestone transitions
const ourLodestones = transitions.filter(t => t.type === 'LODESTONE');
console.log(`\nOur total LODESTONE entries: ${ourLodestones.length}`);

// Named lodestones (with specific names like "Lumbridge Lodestone")
const namedLodestones = ourLodestones.filter(t =>
  t.name && t.name !== 'Lodestone' && t.name !== 'Inactive lodestone' &&
  t.name !== 'Timeworn lodestone' && t.name !== 'Fort Forinthry lodestone' &&
  t.name.includes('Lodestone')
);

// Generic/instance lodestones
const genericLodestones = ourLodestones.filter(t =>
  t.name === 'Lodestone' || t.name === 'Inactive lodestone' ||
  t.name === 'Timeworn lodestone' || t.name === 'Fort Forinthry lodestone'
);

// Any other lodestones
const otherLodestones = ourLodestones.filter(t =>
  !namedLodestones.includes(t) && !genericLodestones.includes(t)
);

console.log(`\nNamed lodestones (e.g. "X Lodestone"): ${namedLodestones.length}`);
console.log(`Generic lodestones ("Lodestone"/"Inactive lodestone"/etc): ${genericLodestones.length}`);
if (otherLodestones.length > 0) {
  console.log(`Other lodestone entries: ${otherLodestones.length}`);
  otherLodestones.forEach(t => console.log(`  "${t.name}" src=${coordStr(t.srcX,t.srcY,t.srcP)} dst=${coordStr(t.dstX,t.dstY,t.dstP)}`));
}

// Breakdown of generic lodestones by name
const genericByName = {};
for (const t of genericLodestones) {
  genericByName[t.name] = (genericByName[t.name] || 0) + 1;
}
console.log('\nGeneric lodestone breakdown:');
for (const [name, count] of Object.entries(genericByName)) {
  console.log(`  "${name}": ${count} entries`);
}

// Check instance-space in generic
const instanceGeneric = genericLodestones.filter(t => isInstanceSpace(t.srcX, t.srcY));
const normalGeneric = genericLodestones.filter(t => !isInstanceSpace(t.srcX, t.srcY));
console.log(`\nGeneric with instance-space src coords: ${instanceGeneric.length}`);
console.log(`Generic with normal src coords: ${normalGeneric.length}`);

// Show unique destinations of generic lodestones
const genericDsts = new Map();
for (const t of genericLodestones) {
  const key = `${t.dstX},${t.dstY},${t.dstP}`;
  if (!genericDsts.has(key)) genericDsts.set(key, 0);
  genericDsts.set(key, genericDsts.get(key) + 1);
}
console.log(`\nUnique DST coordinates in generic lodestones: ${genericDsts.size}`);
for (const [key, count] of genericDsts) {
  console.log(`  (${key}): ${count} entries`);
}

// Compare named lodestone DST coords against reference
console.log('\n--- Named Lodestone DST Coordinate Comparison ---');
const namedByDst = {};
for (const t of namedLodestones) {
  // Extract location name from "X Lodestone"
  const locName = t.name.replace(' Lodestone', '');
  if (!namedByDst[locName]) namedByDst[locName] = [];
  namedByDst[locName].push(t);
}

let lodestoneMatches = 0;
let lodestoneMismatches = 0;
const missingFromOurs = [];

for (const [refName, refCoord] of Object.entries(refLodestones)) {
  // Try to find matching entry in our data
  const ourEntry = namedByDst[refName];
  if (!ourEntry || ourEntry.length === 0) {
    missingFromOurs.push(refName);
    console.log(`  MISSING: ${refName} - ref: ${coordStr(refCoord.x, refCoord.y, refCoord.p)}`);
    continue;
  }

  const t = ourEntry[0];
  const match = t.dstX === refCoord.x && t.dstY === refCoord.y && t.dstP === refCoord.p;
  if (match) {
    lodestoneMatches++;
    console.log(`  MATCH: ${refName} - ${coordStr(refCoord.x, refCoord.y, refCoord.p)}`);
  } else {
    lodestoneMismatches++;
    console.log(`  MISMATCH: ${refName} - ours: ${coordStr(t.dstX, t.dstY, t.dstP)} vs ref: ${coordStr(refCoord.x, refCoord.y, refCoord.p)}`);
  }
}

// Check if we have lodestones not in reference
const refNames = new Set(Object.keys(refLodestones));
const extraInOurs = Object.keys(namedByDst).filter(n => !refNames.has(n));
if (extraInOurs.length > 0) {
  console.log(`\nIn our data but NOT in reference:`);
  for (const name of extraInOurs) {
    const t = namedByDst[name][0];
    console.log(`  ${name}: dst=${coordStr(t.dstX, t.dstY, t.dstP)}`);
  }
}

console.log(`\nLodestone Summary:`);
console.log(`  Reference total: ${Object.keys(refLodestones).length}`);
console.log(`  Our named entries: ${namedLodestones.length} (${Object.keys(namedByDst).length} unique locations)`);
console.log(`  Coordinate matches: ${lodestoneMatches}`);
console.log(`  Coordinate mismatches: ${lodestoneMismatches}`);
console.log(`  Missing from our data: ${missingFromOurs.length} (${missingFromOurs.join(', ')})`);
console.log(`  Instance/duplicate entries: ${genericLodestones.length}`);

// ═══════════════════════════════════════════════════════════════════════
// 2. ITEM TELEPORTS
// ═══════════════════════════════════════════════════════════════════════
console.log('\n' + '='.repeat(80));
console.log('2. ITEM TELEPORT ANALYSIS');
console.log('='.repeat(80));

const refItems = extractItemTeleports(teleportHTML);
const ourItemTeleports = transitions.filter(t => t.type === 'ITEM_TELEPORT');
console.log(`\nOur ITEM_TELEPORT entries: ${ourItemTeleports.length}`);
console.log(`Reference jewelry/special items found: ${Object.keys(refItems).length}`);

// Group our item teleports by name
const ourItemsByName = {};
for (const t of ourItemTeleports) {
  const key = t.name || 'unnamed';
  if (!ourItemsByName[key]) ourItemsByName[key] = [];
  ourItemsByName[key].push(t);
}

console.log(`\nOur unique item teleport names: ${Object.keys(ourItemsByName).length}`);
console.log('\nOur item teleports by name:');
for (const [name, entries] of Object.entries(ourItemsByName).sort((a,b) => b[1].length - a[1].length)) {
  const dsts = new Set(entries.map(e => `${e.dstX},${e.dstY},${e.dstP}`));
  console.log(`  "${name}": ${entries.length} entries, ${dsts.size} unique destinations`);
}

// Check specific reference items against our data
const referenceItemNames = [
  'Ring of duelling', 'Ring of Duelling',
  'Games necklace', 'Games Necklace',
  'Combat bracelet', 'Combat Bracelet',
  'Skills necklace', 'Skills Necklace',
  'Amulet of glory', 'Amulet of Glory',
  'Ring of Wealth', 'Ring of wealth',
  'Pharaoh\'s sceptre', 'Pharaoh\'s Sceptre',
  'Digsite pendant', 'Digsite Pendant',
  'Ectophial',
  'Royal seed pod',
  'TokKul-Zo',
  'Crystal teleport seed',
  'Drakan\'s medallion',
  'Ring of slaying', 'Slayer Ring', 'Slayer ring',
  'Ring of respawn',
  'Enlightened amulet', 'Enlightened Amulet',
  'Traveller\'s necklace', 'Traveller\'s Necklace',
  'Sixth-Age circuit', 'Sixth-Age Circuit',
  'Ring of kinship', 'Ring of Kinship',
  'Luck of the Dwarves', 'Luck of the dwarves',
  'Pontifex Shadow Ring', 'Pontifex shadow ring',
  'Passage of the Abyss',
  'Grace of the Elves',
  'Enchanted Lyre', 'Enchanted lyre',
  'Ferocious ring', 'Ferocious Ring',
  'Hoardstalker ring', 'Hoardstalker Ring',
  'Completionist cape',
  'Wicked hood', 'Wicked Hood',
  'Clan vexillum',
  'Skull sceptre',
  'Ring of Fortune', 'Ring of fortune',
  'Ardougne Cloak', 'Ardougne cloak',
  'Explorer\'s Ring', 'Explorer\'s ring',
  'Fremennik Sea Boots', 'Fremennik sea boots',
  'Karamja Gloves', 'Karamja gloves',
  'Desert Amulet', 'Desert amulet',
  'Morytania Legs', 'Morytania legs',
  'Wilderness Sword', 'Wilderness sword',
  'Attuned crystal teleport seed',
];

// Normalize name for fuzzy matching
function normalizeName(name) {
  return name.toLowerCase().replace(/['\u2019-]/g, '').replace(/\s+/g, ' ').trim();
}

const ourNormalized = {};
for (const name of Object.keys(ourItemsByName)) {
  ourNormalized[normalizeName(name)] = name;
}

console.log('\n--- Reference Item Cross-Check ---');

// Unique canonical names from reference
const canonicalRefItems = [
  'Amulet of Glory', 'Ring of Duelling', 'Games Necklace', 'Skills Necklace',
  'Combat Bracelet', 'Digsite Pendant', 'Enlightened Amulet', "Traveller's Necklace",
  'Slayer Ring', 'TokKul-Zo', 'Sixth-Age Circuit', 'Ring of Kinship',
  'Ring of Fortune / Ring of Wealth', 'Luck of the Dwarves', 'Pontifex Shadow Ring',
  'Passage of the Abyss', 'Grace of the Elves', 'Enchanted Lyre',
  "Pharaoh's Sceptre", 'Ectophial', 'Royal seed pod', "Drakan's medallion",
  'Skull sceptre', 'Clan vexillum', 'Ferocious Ring', 'Hoardstalker Ring',
  'Completionist cape', 'Wicked Hood', 'Crystal teleport seed',
  'Attuned crystal teleport seed', 'Ring of respawn',
  'Ardougne Cloak', "Explorer's Ring", 'Fremennik Sea Boots',
  'Karamja Gloves', 'Desert Amulet', 'Morytania Legs', 'Wilderness Sword',
  'Menaphos tablet', 'Menaphos journal', 'Juju teleport spiritbag',
  'Portable fairy ring', 'Spirit tree re-rooter',
];

const foundInOurs = [];
const missingInOurs = [];

for (const refName of canonicalRefItems) {
  const norm = normalizeName(refName);
  // Try exact, normalized, and partial match
  let found = false;
  for (const ourName of Object.keys(ourItemsByName)) {
    const ourNorm = normalizeName(ourName);
    if (ourNorm === norm || ourNorm.includes(norm) || norm.includes(ourNorm)) {
      foundInOurs.push({ refName, ourName, count: ourItemsByName[ourName].length });
      found = true;
      break;
    }
  }
  if (!found) {
    // Try even broader matching - check if any key word matches
    const refWords = norm.split(' ');
    for (const ourName of Object.keys(ourItemsByName)) {
      const ourNorm = normalizeName(ourName);
      if (refWords.length >= 2 && refWords.every(w => w.length > 2 && ourNorm.includes(w))) {
        foundInOurs.push({ refName, ourName, count: ourItemsByName[ourName].length });
        found = true;
        break;
      }
    }
  }
  if (!found) {
    missingInOurs.push(refName);
  }
}

console.log('\nReference items FOUND in our data:');
for (const item of foundInOurs) {
  console.log(`  [OK] ${item.refName} -> "${item.ourName}" (${item.count} entries)`);
}

console.log(`\nReference items MISSING from our data:`);
for (const name of missingInOurs) {
  console.log(`  [MISSING] ${name}`);
}

console.log(`\nItem Teleport Summary:`);
console.log(`  Our total entries: ${ourItemTeleports.length}`);
console.log(`  Our unique names: ${Object.keys(ourItemsByName).length}`);
console.log(`  Reference items checked: ${canonicalRefItems.length}`);
console.log(`  Found: ${foundInOurs.length}`);
console.log(`  Missing: ${missingInOurs.length}`);

// ═══════════════════════════════════════════════════════════════════════
// 3. SPIRIT TREES
// ═══════════════════════════════════════════════════════════════════════
console.log('\n' + '='.repeat(80));
console.log('3. SPIRIT TREE ANALYSIS');
console.log('='.repeat(80));

const refSpiritTrees = extractSpiritTrees(teleportHTML);
const ourSpiritTrees = transitions.filter(t => t.type === 'SPIRIT_TREE');
console.log(`\nOur SPIRIT_TREE entries: ${ourSpiritTrees.length}`);
console.log(`Reference spirit tree locations: ${refSpiritTrees.length}`);

// Unique source locations
const srcLocs = new Map();
for (const t of ourSpiritTrees) {
  const key = `${t.srcX},${t.srcY},${t.srcP}`;
  if (!srcLocs.has(key)) srcLocs.set(key, { coord: key, names: new Set(), count: 0 });
  srcLocs.get(key).names.add(t.name);
  srcLocs.get(key).count++;
}

// Unique destination locations
const dstLocs = new Map();
for (const t of ourSpiritTrees) {
  const key = `${t.dstX},${t.dstY},${t.dstP}`;
  if (!dstLocs.has(key)) dstLocs.set(key, { coord: key, names: new Set(), count: 0 });
  dstLocs.get(key).names.add(t.name);
  dstLocs.get(key).count++;
}

console.log(`\nUnique source locations: ${srcLocs.size}`);
for (const [key, data] of srcLocs) {
  console.log(`  (${key}): ${data.count} entries, names: ${[...data.names].join(', ')}`);
}

console.log(`\nUnique destination locations: ${dstLocs.size}`);
for (const [key, data] of dstLocs) {
  console.log(`  (${key}): ${data.count} entries, names: ${[...data.names].join(', ')}`);
}

// Group by name
const spiritByName = {};
for (const t of ourSpiritTrees) {
  const key = t.name || 'unnamed';
  if (!spiritByName[key]) spiritByName[key] = [];
  spiritByName[key].push(t);
}
console.log(`\nSpirit tree names in our data:`);
for (const [name, entries] of Object.entries(spiritByName).sort((a,b) => b[1].length - a[1].length)) {
  console.log(`  "${name}": ${entries.length} entries`);
}

// Compare reference destinations against our destination coords
console.log('\n--- Reference Spirit Tree Coordinate Comparison ---');
for (const ref of refSpiritTrees) {
  const refKey = `${ref.x},${ref.y},${ref.p}`;
  const found = dstLocs.has(refKey);
  console.log(`  ${found ? 'FOUND' : 'MISSING'}: ${ref.name} at (${refKey})`);
}

// Duplication analysis
const uniquePairs = new Set();
for (const t of ourSpiritTrees) {
  uniquePairs.add(`${t.srcX},${t.srcY},${t.srcP}->${t.dstX},${t.dstY},${t.dstP}`);
}

console.log(`\nSpirit Tree Summary:`);
console.log(`  Total entries: ${ourSpiritTrees.length}`);
console.log(`  Unique src->dst pairs: ${uniquePairs.size}`);
console.log(`  Unique sources: ${srcLocs.size}`);
console.log(`  Unique destinations: ${dstLocs.size}`);
console.log(`  Reference locations: ${refSpiritTrees.length}`);
console.log(`  Expected combos (${srcLocs.size} src x ${dstLocs.size} dst): ${srcLocs.size * dstLocs.size}`);
console.log(`  Duplication factor: ${(ourSpiritTrees.length / uniquePairs.size).toFixed(1)}x`);

// ═══════════════════════════════════════════════════════════════════════
// 4. GNOME GLIDERS
// ═══════════════════════════════════════════════════════════════════════
console.log('\n' + '='.repeat(80));
console.log('4. GNOME GLIDER ANALYSIS');
console.log('='.repeat(80));

const refGliders = extractGliders(teleportHTML);
const ourGliders = transitions.filter(t => t.type === 'GNOME_GLIDER');
console.log(`\nOur GNOME_GLIDER entries: ${ourGliders.length}`);
console.log(`Reference glider destinations: ${refGliders.length}`);

// Print reference gliders
console.log('\nReference gliders:');
for (const g of refGliders) {
  console.log(`  ${g.codeName} (${g.location}): ${g.coord ? coordStr(g.coord.x, g.coord.y, g.coord.p) : 'Instance'}`);
}

// Group by name
const gliderByName = {};
for (const t of ourGliders) {
  const key = t.name || 'unnamed';
  if (!gliderByName[key]) gliderByName[key] = [];
  gliderByName[key].push(t);
}

console.log(`\nOur glider names:`);
for (const [name, entries] of Object.entries(gliderByName).sort((a,b) => b[1].length - a[1].length)) {
  const dsts = new Set(entries.map(e => `${e.dstX},${e.dstY},${e.dstP}`));
  console.log(`  "${name}": ${entries.length} entries, ${dsts.size} unique destinations`);
}

// Unique source and destination analysis
const gliderSrcs = new Map();
const gliderDsts = new Map();
for (const t of ourGliders) {
  const sk = `${t.srcX},${t.srcY},${t.srcP}`;
  const dk = `${t.dstX},${t.dstY},${t.dstP}`;
  if (!gliderSrcs.has(sk)) gliderSrcs.set(sk, 0);
  gliderSrcs.set(sk, gliderSrcs.get(sk) + 1);
  if (!gliderDsts.has(dk)) gliderDsts.set(dk, 0);
  gliderDsts.set(dk, gliderDsts.get(dk) + 1);
}

console.log(`\nUnique source locations: ${gliderSrcs.size}`);
console.log(`Unique destination locations: ${gliderDsts.size}`);

// Show destination coords
console.log('\nOur unique glider destinations:');
for (const [key, count] of [...gliderDsts].sort((a,b) => b[1] - a[1])) {
  // Find a name for this destination
  const entry = ourGliders.find(t => `${t.dstX},${t.dstY},${t.dstP}` === key);
  console.log(`  (${key}): ${count} entries -> name: "${entry?.name}"`);
}

// Compare reference against our destinations
console.log('\n--- Reference Glider Coordinate Comparison ---');
for (const ref of refGliders) {
  if (!ref.coord) {
    console.log(`  SKIP (instance): ${ref.codeName} (${ref.location})`);
    continue;
  }
  const refKey = `${ref.coord.x},${ref.coord.y},${ref.coord.p}`;
  const found = gliderDsts.has(refKey);
  console.log(`  ${found ? 'FOUND' : 'MISSING'}: ${ref.codeName} (${ref.location}) at (${refKey})`);
}

// Categorize entries
const destNamedEntries = ourGliders.filter(t => t.name && t.name.includes('('));
const npcNamedEntries = ourGliders.filter(t => t.name && !t.name.includes('('));
console.log(`\nGnome Glider Summary:`);
console.log(`  Total entries: ${ourGliders.length}`);
console.log(`  Destination-named entries (e.g. "Ta Quir Priw (Grand Tree)"): ${destNamedEntries.length}`);
console.log(`  NPC-named entries (e.g. "Captain Dalbur"): ${npcNamedEntries.length}`);
console.log(`  Unique sources: ${gliderSrcs.size}`);
console.log(`  Unique destinations: ${gliderDsts.size}`);
console.log(`  Reference destinations: ${refGliders.length}`);

// ═══════════════════════════════════════════════════════════════════════
// 5. FAIRY RINGS
// ═══════════════════════════════════════════════════════════════════════
console.log('\n' + '='.repeat(80));
console.log('5. FAIRY RING ANALYSIS');
console.log('='.repeat(80));

const refFairyRings = extractFairyRingCodes(teleportHTML);
const ourFairyRings = transitions.filter(t => t.type === 'FAIRY_RING');
console.log(`\nOur FAIRY_RING entries: ${ourFairyRings.length}`);
console.log(`Reference fairy ring codes: ${refFairyRings.length}`);

// Group by name to find codes
const fairyByName = {};
for (const t of ourFairyRings) {
  const key = t.name || 'unnamed';
  if (!fairyByName[key]) fairyByName[key] = [];
  fairyByName[key].push(t);
}

console.log(`\nOur fairy ring names:`);
for (const [name, entries] of Object.entries(fairyByName).sort((a,b) => b[1].length - a[1].length)) {
  const dsts = new Set(entries.map(e => `${e.dstX},${e.dstY},${e.dstP}`));
  console.log(`  "${name}": ${entries.length} entries, ${dsts.size} unique destinations`);
}

// Extract codes from our data (codes are typically in the name or option)
const ourCodes = new Set();
const codePattern = /\b([A-Z]{3})\b/;
for (const t of ourFairyRings) {
  const nameMatch = (t.name || '').match(codePattern);
  const optMatch = (t.option || '').match(codePattern);
  if (nameMatch) ourCodes.add(nameMatch[1]);
  if (optMatch) ourCodes.add(optMatch[1]);
}

// Also try to extract codes from destination names
// Many fairy ring entries might use format like "Fairy ring (AIQ)"
const codePattern2 = /\(([A-Z]{3})\)/;
for (const t of ourFairyRings) {
  const m1 = (t.name || '').match(codePattern2);
  if (m1) ourCodes.add(m1[1]);
}

console.log(`\nFairy ring codes found in our names: ${ourCodes.size}`);
if (ourCodes.size > 0) {
  console.log(`  Codes: ${[...ourCodes].sort().join(', ')}`);
}

// Unique destinations
const fairyDsts = new Map();
for (const t of ourFairyRings) {
  const key = `${t.dstX},${t.dstY},${t.dstP}`;
  if (!fairyDsts.has(key)) fairyDsts.set(key, { count: 0, names: new Set() });
  fairyDsts.get(key).count++;
  fairyDsts.get(key).names.add(t.name);
}

console.log(`\nUnique destination coordinates: ${fairyDsts.size}`);

// Unique sources
const fairySrcs = new Map();
for (const t of ourFairyRings) {
  const key = `${t.srcX},${t.srcY},${t.srcP}`;
  if (!fairySrcs.has(key)) fairySrcs.set(key, 0);
  fairySrcs.set(key, fairySrcs.get(key) + 1);
}
console.log(`Unique source locations: ${fairySrcs.size}`);

// Compare reference codes against our codes
const refCodes = refFairyRings.map(r => r.code);
const refCodeSet = new Set(refCodes);
const missingCodes = refCodes.filter(c => !ourCodes.has(c));
const extraCodes = [...ourCodes].filter(c => !refCodeSet.has(c));

if (ourCodes.size > 0) {
  console.log(`\nReference codes missing from our data: ${missingCodes.length}`);
  if (missingCodes.length > 0) {
    for (const code of missingCodes) {
      const ref = refFairyRings.find(r => r.code === code);
      console.log(`  [MISSING] ${code} - ${ref?.destination}`);
    }
  }

  console.log(`\nExtra codes in our data (not in reference): ${extraCodes.length}`);
  if (extraCodes.length > 0) console.log(`  ${extraCodes.join(', ')}`);
}

// Check if names contain destination info instead of codes
console.log('\n--- Sample fairy ring entries (first 20) ---');
for (const t of ourFairyRings.slice(0, 20)) {
  console.log(`  "${t.name}" opt="${t.option}" src=${coordStr(t.srcX,t.srcY,t.srcP)} dst=${coordStr(t.dstX,t.dstY,t.dstP)}`);
}

// Unique src->dst pairs
const fairyPairs = new Set();
for (const t of ourFairyRings) {
  fairyPairs.add(`${t.srcX},${t.srcY},${t.srcP}->${t.dstX},${t.dstY},${t.dstP}`);
}

console.log(`\nFairy Ring Summary:`);
console.log(`  Total entries: ${ourFairyRings.length}`);
console.log(`  Unique src->dst pairs: ${fairyPairs.size}`);
console.log(`  Unique sources: ${fairySrcs.size}`);
console.log(`  Unique destinations: ${fairyDsts.size}`);
console.log(`  Our unique codes: ${ourCodes.size}`);
console.log(`  Reference codes: ${refFairyRings.length}`);

// ═══════════════════════════════════════════════════════════════════════
// OVERALL SUMMARY
// ═══════════════════════════════════════════════════════════════════════
console.log('\n' + '='.repeat(80));
console.log('OVERALL SUMMARY');
console.log('='.repeat(80));

const typeCount = {};
for (const t of transitions) {
  typeCount[t.type] = (typeCount[t.type] || 0) + 1;
}

console.log('\nAll transition types:');
for (const [type, count] of Object.entries(typeCount).sort((a,b) => b[1] - a[1])) {
  console.log(`  ${type}: ${count}`);
}

console.log(`\nTotal transitions: ${transitions.length}`);
