/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.api.DMLScript.RUNTIME_PLATFORM;
import com.ibm.bi.dml.lops.Aggregate;
import com.ibm.bi.dml.lops.CentralMoment;
import com.ibm.bi.dml.lops.CoVariance;
import com.ibm.bi.dml.lops.CombineBinary;
import com.ibm.bi.dml.lops.CombineTertiary;
import com.ibm.bi.dml.lops.Group;
import com.ibm.bi.dml.lops.Lop;
import com.ibm.bi.dml.lops.LopsException;
import com.ibm.bi.dml.lops.PickByCount;
import com.ibm.bi.dml.lops.ReBlock;
import com.ibm.bi.dml.lops.SortKeys;
import com.ibm.bi.dml.lops.Tertiary;
import com.ibm.bi.dml.lops.UnaryCP;
import com.ibm.bi.dml.lops.CombineBinary.OperationTypes;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.PartialAggregate.CorrectionLocationType;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.sql.sqllops.ISQLSelect;
import com.ibm.bi.dml.sql.sqllops.SQLCondition;
import com.ibm.bi.dml.sql.sqllops.SQLJoin;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties;
import com.ibm.bi.dml.sql.sqllops.SQLLops;
import com.ibm.bi.dml.sql.sqllops.SQLSelectStatement;
import com.ibm.bi.dml.sql.sqllops.SQLTableReference;
import com.ibm.bi.dml.sql.sqllops.SQLUnion;
import com.ibm.bi.dml.sql.sqllops.SQLCondition.BOOLOP;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties.AGGREGATIONTYPE;
import com.ibm.bi.dml.sql.sqllops.SQLLopProperties.JOINTYPE;
import com.ibm.bi.dml.sql.sqllops.SQLLops.GENERATES;
import com.ibm.bi.dml.sql.sqllops.SQLUnion.UNIONTYPE;

/* Primary use cases for now, are
 * 		quantile (<n-1-matrix>, <n-1-matrix>, <literal>):      quantile (A, w, 0.5)
 * 		quantile (<n-1-matrix>, <n-1-matrix>, <scalar>):       quantile (A, w, s)
 * 		interquantile (<n-1-matrix>, <n-1-matrix>, <scalar>):  interquantile (A, w, s)
 * 
 * Keep in mind, that we also have binaries for it w/o weights.
 * 	quantile (A, 0.5)
 * 	quantile (A, s)
 * 	interquantile (A, s)
 *
 */

public class TertiaryOp extends Hop 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	Hop.OpOp3 op;

	private TertiaryOp() {
		//default constructor for clone
	}
	
	public TertiaryOp(String l, DataType dt, ValueType vt, Hop.OpOp3 o,
			Hop inp1, Hop inp2, Hop inp3) {
		super(Hop.Kind.TertiaryOp, l, dt, vt);
		op = o;
		getInput().add(0, inp1);
		getInput().add(1, inp2);
		getInput().add(2, inp3);
		inp1.getParent().add(this);
		inp2.getParent().add(this);
		inp3.getParent().add(this);
	}
	
	@Override
	public Lop constructLops() 
		throws HopsException, LopsException 
	{	
		if (get_lops() == null) {
			try {
				switch(op) {
				case CENTRALMOMENT:
					handleCentralMoment();
					break;
					
				case COVARIANCE:
					handleCovariance();
					break;
					
				case QUANTILE:
				case INTERQUANTILE:
					handleQuantile();
					break;
					
				case CTABLE:
					handleCtable();
					break;
					
					default:
						throw new HopsException(this.printErrorLocation() + "Unknown TertiaryOp (" + op + ") while constructing Lops \n");

				}
			} catch(LopsException e) {
				throw new HopsException(this.printErrorLocation() + "error constructing Lops for TertiaryOp Hop " , e);
			}
		}
	
		return get_lops();
	}

	/**
	 * Method to construct LOPs when op = CENTRAILMOMENT.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void handleCentralMoment() throws HopsException, LopsException {
		
		if ( op != OpOp3.CENTRALMOMENT )
			throw new HopsException("Unexpected operation: " + op + ", expecting " + OpOp3.CENTRALMOMENT );
		
		ExecType et = optFindExecType();
		if ( et == ExecType.MR ) {
			CombineBinary combine = CombineBinary.constructCombineLop(
					OperationTypes.PreCentralMoment, 
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					DataType.MATRIX, get_valueType());
			combine.getOutputParameters().setDimensions(
					getInput().get(0).get_dim1(),
					getInput().get(0).get_dim2(),
					getInput().get(0).get_rows_in_block(),
					getInput().get(0).get_cols_in_block(), 
					getInput().get(0).getNnz());
			
			CentralMoment cm = new CentralMoment(combine, getInput()
					.get(2).constructLops(), DataType.MATRIX,
					get_valueType(), et);
			cm.getOutputParameters().setDimensions(1, 1, 0, 0, -1);
			
			cm.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			UnaryCP unary1 = new UnaryCP(cm, HopsOpOp1LopsUS
					.get(OpOp1.CAST_AS_SCALAR), get_dataType(),
					get_valueType());
			unary1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			unary1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			set_lops(unary1);
		} else {
			//System.out.println("CM Tertiary executing in CP...");
			CentralMoment cm = new CentralMoment(
					getInput().get(0).constructLops(),
					getInput().get(1).constructLops(),
					getInput().get(2).constructLops(),
					get_dataType(), get_valueType(), et);
			cm.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			cm.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			set_lops(cm);
		}
	}
	
	/**
	 * Method to construct LOPs when op = COVARIANCE.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void handleCovariance() throws HopsException, LopsException {
		
		if ( op != OpOp3.COVARIANCE )
			throw new HopsException("Unexpected operation: " + op + ", expecting " + OpOp3.COVARIANCE );
		
		ExecType et = optFindExecType();
		if ( et == ExecType.MR ) {
			// combineTertiary -> CoVariance -> CastAsScalar
			CombineTertiary combine = CombineTertiary
					.constructCombineLop(
							CombineTertiary.OperationTypes.PreCovWeighted,
							getInput().get(0).constructLops(),
							getInput().get(1).constructLops(),
							getInput().get(2).constructLops(),
							DataType.MATRIX, get_valueType());

			combine.getOutputParameters().setDimensions(
					getInput().get(0).get_dim1(),
					getInput().get(0).get_dim2(),
					getInput().get(0).get_rows_in_block(),
					getInput().get(0).get_cols_in_block(), 
					getInput().get(0).getNnz());

			CoVariance cov = new CoVariance(
					combine, DataType.MATRIX, get_valueType(), et);

			cov.getOutputParameters().setDimensions(1, 1, 0, 0, -1);
			
			cov.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			UnaryCP unary1 = new UnaryCP(
					cov, HopsOpOp1LopsUS.get(OpOp1.CAST_AS_SCALAR),
					get_dataType(), get_valueType());
			unary1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			unary1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			set_lops(unary1);
		}
		else {
			//System.out.println("COV Tertiary executing in CP...");
			CoVariance cov = new CoVariance(
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					getInput().get(2).constructLops(), 
					get_dataType(), get_valueType(), et);
			cov.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			cov.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			set_lops(cov);
		}
	}
	
	/**
	 * Method to construct LOPs when op = QUANTILE | INTERQUANTILE.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void handleQuantile() throws HopsException, LopsException {
		
		if ( op != OpOp3.QUANTILE && op != OpOp3.INTERQUANTILE )
			throw new HopsException("Unexpected operation: " + op + ", expecting " + OpOp3.QUANTILE + " or " + OpOp3.INTERQUANTILE );
		
		ExecType et = optFindExecType();
		
		if ( et == ExecType.MR ) {
			CombineBinary combine = CombineBinary
					.constructCombineLop(
							OperationTypes.PreSort,
							getInput().get(0).constructLops(),
							getInput().get(1).constructLops(),
							DataType.MATRIX, get_valueType());

			SortKeys sort = SortKeys
					.constructSortByValueLop(
							combine,
							SortKeys.OperationTypes.WithWeights,
							DataType.MATRIX, get_valueType(), et);

			// If only a single quantile is computed, then "pick" operation executes in CP.
			ExecType et_pick = (getInput().get(2).get_dataType() == DataType.SCALAR ? ExecType.CP : ExecType.MR);
			PickByCount pick = new PickByCount(
					sort,
					getInput().get(2).constructLops(),
					get_dataType(),
					get_valueType(),
					(op == Hop.OpOp3.QUANTILE) ? PickByCount.OperationTypes.VALUEPICK
							: PickByCount.OperationTypes.RANGEPICK, et_pick, false);

			pick.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			combine.getOutputParameters().setDimensions(
					getInput().get(0).get_dim1(),
					getInput().get(0).get_dim2(), 
					getInput().get(0).get_rows_in_block(), 
					getInput().get(0).get_cols_in_block(),
					getInput().get(0).getNnz());
			sort.getOutputParameters().setDimensions(
					getInput().get(0).get_dim1(),
					getInput().get(0).get_dim2(), 
					getInput().get(0).get_rows_in_block(), 
					getInput().get(0).get_cols_in_block(),
					getInput().get(0).getNnz());
			pick.getOutputParameters().setDimensions(get_dim1(),
					get_dim2(), get_rows_in_block(), get_cols_in_block(), getNnz());

			set_lops(pick);
		}
		else {
			SortKeys sort = SortKeys.constructSortByValueLop(
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					SortKeys.OperationTypes.WithWeights, 
					getInput().get(0).get_dataType(), getInput().get(0).get_valueType(), et);
			PickByCount pick = new PickByCount(
					sort,
					getInput().get(2).constructLops(),
					get_dataType(),
					get_valueType(),
					(op == Hop.OpOp3.QUANTILE) ? PickByCount.OperationTypes.VALUEPICK
							: PickByCount.OperationTypes.RANGEPICK, et, true);
			sort.getOutputParameters().setDimensions(
					getInput().get(0).get_dim1(),
					getInput().get(0).get_dim2(),
					getInput().get(0).get_rows_in_block(), 
					getInput().get(0).get_cols_in_block(),
					getInput().get(0).getNnz());
			pick.getOutputParameters().setDimensions(get_dim1(),
					get_dim2(), get_rows_in_block(), get_cols_in_block(), getNnz());

			set_lops(pick);
		}
	}

	/**
	 * Method to construct LOPs when op = CTABLE.
	 * 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void handleCtable() throws HopsException, LopsException {
		
		if ( op != OpOp3.CTABLE )
			throw new HopsException("Unexpected operation: " + op + ", expecting " + OpOp3.CTABLE );
		
		/*
		 * We must handle three different cases: case1 : all three
		 * inputs are vectors (e.g., F=ctable(A,B,W)) case2 : two
		 * vectors and one scalar (e.g., F=ctable(A,B)) case3 : one
		 * vector and two scalars (e.g., F=ctable(A))
		 */

		// identify the particular case
		
		// F=ctable(A,B,W)
		
		DataType dt1 = getInput().get(0).get_dataType(); 
		DataType dt2 = getInput().get(1).get_dataType(); 
		DataType dt3 = getInput().get(2).get_dataType(); 
		Tertiary.OperationTypes tertiaryOp = Tertiary.findCtableOperationByInputDataTypes(dt1, dt2, dt3);
		
		ExecType et = optFindExecType();
		if ( et == ExecType.CP ) {
			Tertiary tertiary = new Tertiary(
					getInput().get(0).constructLops(), 
					getInput().get(1).constructLops(), 
					getInput().get(2).constructLops(),
					tertiaryOp,
					get_dataType(), get_valueType(), et);
			tertiary.getOutputParameters().setDimensions(-1, -1, -1, -1, -1);
			tertiary.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			if ( DMLScript.rtplatform == RUNTIME_PLATFORM.SINGLE_NODE 
				|| et == ExecType.CP ) {
				
				if( et == ExecType.CP ){
					//force blocked output in CP (see below)
					tertiary.getOutputParameters().setDimensions(-1, -1, 1000, 1000, -1);
				}
				
				//tertiary opt, w/o reblock in CP
				set_lops(tertiary);
			}
			else {
				ReBlock reblock = null;
				try {
					reblock = new ReBlock(
							tertiary, get_rows_in_block(),
							get_cols_in_block(), get_dataType(),
							get_valueType());
				} catch (Exception e) {
					throw new HopsException(this.printErrorLocation() + "error in constructLops for TertiaryOp Hop " , e);
				}
				reblock.getOutputParameters().setDimensions(-1, -1,  
						get_rows_in_block(), get_cols_in_block(), -1);
				
				reblock.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());

				set_lops(reblock);
			}
		}
		else {
			Group group1, group2, group3, group4;
			group1 = group2 = group3 = group4 = null;

			group1 = new Group(
					getInput().get(0).constructLops(),
					Group.OperationTypes.Sort, get_dataType(),
					get_valueType());
			group1.getOutputParameters().setDimensions(get_dim1(),
					get_dim2(), get_rows_in_block(), get_cols_in_block(), getNnz());
			
			group1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());

			Tertiary tertiary = null;
			// create "group" lops for MATRIX inputs
			switch (tertiaryOp) {
			case CTABLE_TRANSFORM:
				// F = ctable(A,B,W)
				group2 = new Group(
						getInput().get(1).constructLops(),
						Group.OperationTypes.Sort, get_dataType(),
						get_valueType());
				group2.getOutputParameters().setDimensions(get_dim1(),
						get_dim2(), get_rows_in_block(),
						get_cols_in_block(), getNnz());
				group2.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
				
				group3 = new Group(
						getInput().get(2).constructLops(),
						Group.OperationTypes.Sort, get_dataType(),
						get_valueType());
				group3.getOutputParameters().setDimensions(get_dim1(),
						get_dim2(), get_rows_in_block(),
						get_cols_in_block(), getNnz());
				group3.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
				
				tertiary = new Tertiary(
						group1, group2, group3,
						tertiaryOp,
						get_dataType(), get_valueType(), et);	
				
				break;

			case CTABLE_TRANSFORM_SCALAR_WEIGHT:
				// F = ctable(A,B) or F = ctable(A,B,1)
				group2 = new Group(
						getInput().get(1).constructLops(),
						Group.OperationTypes.Sort, get_dataType(),
						get_valueType());
				group2.getOutputParameters().setDimensions(get_dim1(),
						get_dim2(), get_rows_in_block(),
						get_cols_in_block(), getNnz());
				group2.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
				tertiary = new Tertiary(
						group1,
						group2,
						getInput().get(2).constructLops(),
						tertiaryOp,
						get_dataType(), get_valueType(), et);
				break;
			case CTABLE_TRANSFORM_HISTOGRAM:
				// F=ctable(A,1) or F = ctable(A,1,1)
				tertiary = new Tertiary(
						group1, getInput().get(1).constructLops(),
						getInput().get(2).constructLops(),
						tertiaryOp,
						get_dataType(), get_valueType(), et);
				break;
			case CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM:
				// F=ctable(A,1,W)
				group3 = new Group(
						getInput().get(2).constructLops(),
						Group.OperationTypes.Sort, get_dataType(),
						get_valueType());
				group3.getOutputParameters().setDimensions(get_dim1(),
						get_dim2(), get_rows_in_block(),
						get_cols_in_block(), getNnz());
				group3.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
				tertiary = new Tertiary(
						group1,
						getInput().get(1).constructLops(),
						group3,
						tertiaryOp,
						get_dataType(), get_valueType(), et);
				break;
			}

			// output dimensions are not known at compilation time
			tertiary.getOutputParameters().setDimensions(-1, -1, -1, -1, -1);
			tertiary.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			group4 = new Group(
					tertiary, Group.OperationTypes.Sort, get_dataType(),
					get_valueType());
			group4.getOutputParameters().setDimensions(-1, -1, -1, -1, -1);
			group4.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());

			Aggregate agg1 = new Aggregate(
					group4, HopsAgg2Lops.get(AggOp.SUM), get_dataType(),
					get_valueType(), ExecType.MR);
			agg1.getOutputParameters().setDimensions(-1, -1, -1, -1, -1);

			agg1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			// kahamSum is used for aggreagtion but inputs do not have
			// correction values
			agg1.setupCorrectionLocation(CorrectionLocationType.NONE);

			// set_lops(agg1);

			ReBlock reblock = null;
			try {
				reblock = new ReBlock(
						agg1, get_rows_in_block(),
						get_cols_in_block(), get_dataType(),
						get_valueType());
			} catch (Exception e) {
				throw new HopsException(this.printErrorLocation() + "error constructing Lops for TertiaryOp Hop " , e);
			}
			reblock.getOutputParameters().setDimensions(-1, -1, 
					get_rows_in_block(), get_cols_in_block(), -1);

			reblock.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
			set_lops(reblock);
		}
	}
	
	@Override
	public String getOpString() {
		String s = new String("");
		s += "t(" + HopsOpOp3String.get(op) + ")";
		return s;
	}

	public void printMe() throws HopsException {
		if (LOG.isDebugEnabled()){
			if (get_visited() != VISIT_STATUS.DONE) {
				super.printMe();
				LOG.debug("  Operation: " + op);
				for (Hop h : getInput()) {
					h.printMe();
				}
			}
			set_visited(VISIT_STATUS.DONE);
		}
	}

	@Override
	public SQLLops constructSQLLOPs() throws HopsException {
		if (this.op == OpOp3.CTABLE) {
			if (this.getInput().size() != 3)
				throw new HopsException("A tertiary Hop must have three inputs \n");

			GENERATES gen = determineGeneratesFlag();

			Hop hop1 = this.getInput().get(0);
			Hop hop2 = this.getInput().get(1);
			Hop hop3 = this.getInput().get(2);

			hop3.constructSQLLOPs();

			SQLLops maxsqllop = new SQLLops("", GENERATES.SQL, hop1
					.constructSQLLOPs(), hop2.constructSQLLOPs(),
					ValueType.DOUBLE, DataType.MATRIX);

			maxsqllop.set_properties(new SQLLopProperties());
			String resultName = "result_" + this.get_name() + "_"
					+ this.getHopID();
			String maxName = "maximum_" + this.get_name() + "_"
					+ this.getHopID();

			String qhop1name = SQLLops.addQuotes(hop1.get_sqllops()
					.get_tableName());
			String qhop2name = SQLLops.addQuotes(hop2.get_sqllops()
					.get_tableName());
			String qhop3name = SQLLops.addQuotes(hop3.get_sqllops()
					.get_tableName());

			String maxsql = String.format(SQLLops.MAXVAL2TABLES, qhop1name,
					qhop2name);
			maxsqllop.set_sql(maxsql);
			maxsqllop.set_tableName(maxName);
			maxsqllop.set_properties(new SQLLopProperties());
			SQLLopProperties maxprop = new SQLLopProperties();
			maxprop.setJoinType(JOINTYPE.NONE);
			maxprop.setAggType(AGGREGATIONTYPE.NONE);
			maxprop.setOpString("maxrow(" + hop1.get_sqllops().get_tableName()
					+ "),\r\n,maxcol(" + hop2.get_sqllops().get_tableName()
					+ ")");

			maxsqllop.set_properties(maxprop);

			SQLLops resultsqllop = new SQLLops("", GENERATES.SQL, hop1
					.constructSQLLOPs(), hop2.constructSQLLOPs(),
					ValueType.DOUBLE, DataType.MATRIX);

			String resultsql = String.format(SQLLops.CTABLE, qhop1name,
					qhop2name, qhop3name);
			resultsqllop.set_tableName(resultName);
			resultsqllop.set_sql(resultsql);

			SQLLopProperties resprop = new SQLLopProperties();
			resprop.setJoinType(JOINTYPE.TWO_INNERJOINS);
			resprop.setAggType(AGGREGATIONTYPE.SUM);
			resprop.setOpString("CTable(" + hop1.get_sqllops().get_tableName()
					+ ", " + hop2.get_sqllops().get_tableName() + ", "
					+ hop3.get_sqllops().get_tableName() + ")");

			resultsqllop.set_properties(resprop);

			SQLLops sqllop = new SQLLops(this.get_name(), gen, resultsqllop,
					maxsqllop, this.get_valueType(), this.get_dataType());

			// TODO Uncomment this to make scalar placeholders
			if (this.get_dataType() == DataType.SCALAR && gen == GENERATES.DML)
				sqllop.set_tableName("##" + sqllop.get_tableName() + "##");

			String qResultName = SQLLops.addQuotes(resultName);
			String qMaxName = SQLLops.addQuotes(maxName);
			sqllop.set_sql(String.format(SQLLops.ATTACHLASTZERO, qResultName,
					qMaxName, qResultName, qMaxName));

			SQLLopProperties zeroprop = new SQLLopProperties();
			zeroprop.setJoinType(JOINTYPE.NONE);
			zeroprop.setAggType(AGGREGATIONTYPE.NONE);
			zeroprop.setOpString("Last cell not empty");

			this.set_sqllops(sqllop);

			return sqllop;
		}
		return null;
	}

	private ISQLSelect getCTableSelect(String name1, String name2, String name3) {
		SQLSelectStatement stmt = new SQLSelectStatement();
		stmt.getColumns().add("alias_a.value as row");
		stmt.getColumns().add("alias_b.value as col");
		stmt.getColumns().add("sum(alias_c.value) as value");

		SQLJoin j1 = new SQLJoin();
		j1.setJoinType(JOINTYPE.INNERJOIN);
		j1.setTable1(new SQLTableReference(name1));

		SQLJoin j2 = new SQLJoin();
		j2.setJoinType(JOINTYPE.INNERJOIN);
		j2.setTable1(new SQLTableReference(name2));
		j2.setTable2(new SQLTableReference(name3));
		j2.getConditions().add(new SQLCondition("alias_a.row = alias_b.row"));
		j2.getConditions().add(
				new SQLCondition(BOOLOP.AND, "alias_a.col = alias_b.col"));
		j1.setTable2(j2);
		j1.getConditions().add(new SQLCondition("alias_c.row = alias_a.row"));
		j1.getConditions().add(
				new SQLCondition(BOOLOP.AND, "alias_c.col = alias_a.col"));

		stmt.setTable(j1);
		stmt.getGroupBys().add("alias_a.value");
		stmt.getGroupBys().add("alias_b.value");
		return stmt;
	}

	private ISQLSelect getMaxrow2TablesSelect(String name1, String name2) {
		SQLSelectStatement stmt = new SQLSelectStatement();
		stmt.getColumns().add("max(alias_a.value) AS mrow");
		stmt.getColumns().add("max(alias_b.value) AS mcol");

		SQLJoin crossJoin = new SQLJoin();
		crossJoin.setJoinType(JOINTYPE.CROSSJOIN);
		crossJoin.setTable1(new SQLTableReference(name1, SQLLops.ALIAS_A));
		crossJoin.setTable2(new SQLTableReference(name2, SQLLops.ALIAS_B));
		stmt.setTable(crossJoin);

		return stmt;
	}

	private ISQLSelect getAttachLastZeroSelect(String res, String max) {
		SQLUnion union = new SQLUnion();
		SQLSelectStatement stmt1 = new SQLSelectStatement();
		SQLSelectStatement stmt2 = new SQLSelectStatement();

		stmt1.getColumns().add("*");
		stmt1.setTable(new SQLTableReference(res));

		stmt2.getColumns().add("mrow AS row");
		stmt2.getColumns().add("mcol AS col");
		stmt2.getColumns().add("0 AS value");

		stmt2.setTable(new SQLTableReference(max));

		SQLSelectStatement tmp = new SQLSelectStatement();
		SQLJoin join = new SQLJoin();
		join.setJoinType(JOINTYPE.INNERJOIN);
		join.setTable1(new SQLTableReference(res));
		join.setTable2(new SQLTableReference(max));
		join.getConditions().add(new SQLCondition("mrow = row"));
		join.getConditions().add(new SQLCondition(BOOLOP.AND, "mcol = col"));
		tmp.setTable(join);
		tmp.getColumns().add("*");

		stmt2.getWheres().add(
				new SQLCondition("NOT EXISTS (" + tmp.toString() + ")"));
		union.setSelect1(stmt1);
		union.setSelect2(stmt2);
		union.setUnionType(UNIONTYPE.UNIONALL);
		return union;
	}

	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}

	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{
		//only quantile and ctable produce matrices
		
		double sparsity = 1.0;
		
		switch(op) 
		{
			case CTABLE:
				// since the dimensions of both inputs must be the same, checking for one input is sufficient
				//   worst case dimensions of C = [m,m]
				//   worst case #nnz in C = m => sparsity = 1/m
				sparsity = OptimizerUtils.getSparsity(dim1, dim2, (nnz<=dim1)?nnz:dim1); 
				break;
			
			case QUANTILE:
				// This part of the code is executed only when a vector of quantiles are computed
				// Output is a vector of length = #of quantiles to be computed, and it is likely to be dense.
				sparsity = 1.0;
				break;
			
			default:
				throw new RuntimeException("Memory for operation (" + op + ") can not be estimated.");
		}
		
		return OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, sparsity);

		
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{
		double ret = 0;
		if( op == OpOp3.CTABLE ){
			// hashmap for ctable computation, num distinct counts, worst-case dim1 
			ret =  2*4 * dim1 + //hash table (worst-case overhead 2x)
				   32 * dim1; //values: 2xint,1xObject
		}
		else if ( op == OpOp3.QUANTILE ) {
			// buffer (=2*input_size) and output (=input_size) for SORT operation
			// getMemEstimate works for both cases of known dims and worst-case stats
			ret = getInput().get(0).getMemEstimate() * 3;  
		}
		
		return ret;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		long[] ret = null;
	
		MatrixCharacteristics mc[] = memo.getAllInputStats(getInput());
		
		switch(op) 
		{
			case CTABLE:
				// since the dimensions of both inputs must be the same, checking for one input is sufficient
				if( mc[0].dimsKnown() || mc[1].dimsKnown() ) {
					// Output dimensions are completely data dependent. In the worst case, 
					// #categories in each attribute = #rows (e.g., an ID column, say EmployeeID).
					// both inputs are one-dimensional matrices with exact same dimensions, m = size of longer dimension
					long m = (mc[0].dimsKnown())
					          ? (mc[0].get_rows() > 1 ? mc[0].get_rows() : mc[0].get_cols() )
							  : (mc[1].get_rows() > 1 ? mc[1].get_rows() : mc[1].get_cols() );
					ret = new long[]{m, m, m};
				}
				break;
			
			case QUANTILE:
				if( mc[2].dimsKnown() )
					return new long[]{mc[2].get_rows(), 1, mc[2].get_rows()};
				break;
			
			default:
				throw new RuntimeException("Memory for operation (" + op + ") can not be estimated.");
		}
				
		return ret;
	}
	

	@Override
	protected ExecType optFindExecType() throws HopsException {
		
		checkAndSetForcedPlatform();
		
		if( _etypeForced != null ) 			
			_etype = _etypeForced;
		else
		{	
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			else if ( (getInput().get(0).areDimsBelowThreshold() 
					&& getInput().get(1).areDimsBelowThreshold()
					&& getInput().get(2).areDimsBelowThreshold()) 
					//|| (getInput().get(0).isVector() && getInput().get(1).isVector() && getInput().get(1).isVector() )
				)
				_etype = ExecType.CP;
			else
				_etype = ExecType.MR;
			
			//mark for recompile (forever)
			if( OptimizerUtils.ALLOW_DYN_RECOMPILATION && !dimsKnown(true) && _etype==ExecType.MR )
				setRequiresRecompile();
		}
		return _etype;
	}
	
	@Override
	public void refreshSizeInformation()
	{
		if ( get_dataType() == DataType.SCALAR ) 
		{
			//do nothing always known
		}
		else 
		{
			switch(op) 
			{
				case CTABLE:
					//do nothing because the output size is data dependent
					break;
				
				case QUANTILE:
					// This part of the code is executed only when a vector of quantiles are computed
					// Output is a vector of length = #of quantiles to be computed, and it is likely to be dense.
					// TODO qx1
					break;	
					
				default:
					throw new RuntimeException("Size information for operation (" + op + ") can not be updated.");
			}
		}	
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		TertiaryOp ret = new TertiaryOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret.op = op;
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( that._kind!=Kind.TertiaryOp )
			return false;
		
		TertiaryOp that2 = (TertiaryOp)that;	
		return (   op == that2.op
				&& getInput().get(0) == that2.getInput().get(0)
				&& getInput().get(1) == that2.getInput().get(1)
				&& getInput().get(2) == that2.getInput().get(2));
	}
}
